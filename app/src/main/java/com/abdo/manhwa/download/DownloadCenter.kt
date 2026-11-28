package com.abdo.manhwa.download

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.abdo.manhwa.data.repository.FileStorageManager
import com.abdo.manhwa.data.repository.MangaRepository
import com.abdo.manhwa.data.repository.normalizeTitleKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class DlStatus { QUEUED, EXTRACTING, DOWNLOADING, PAUSED, DONE, FAILED }

/** طلب تحميل فصل واحد */
data class DlRequest(
    val mangaTitle: String,
    val mangaDescription: String,
    val coverUrl: String?,
    val chapterTitle: String,
    val chapterUrl: String,
    /** رابط صفحة المانهوا (لجلب الغلاف والنبذة تلقائياً إن لم تُعطَ) */
    val mangaUrl: String? = null,
    /** روابط الصور إن كانت معروفة مسبقاً (من الصفحة المفتوحة)، فنتجاوز فحص الصفحة المخفية */
    val imageUrls: List<String>? = null
)

/** حالة مهمة تحميل فصل واحد. المفتاح هو رابط صفحة الفصل. */
data class DlTask(
    val key: String,
    val mangaTitle: String,
    val mangaDescription: String,
    val coverUrl: String?,
    val chapterTitle: String,
    val mangaUrl: String? = null,
    val status: DlStatus = DlStatus.QUEUED,
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null
) {
    val percent: Float get() = if (total > 0) done.toFloat() / total else 0f
}

/**
 * مركز التحميل. كل فصل يمرّ بثلاث مراحل:
 * 1) فحص صفحة الفصل (في صفحة مخفية) لجمع روابط الصور، أو استخدام الروابط الجاهزة من الصفحة المفتوحة.
 * 2) تحميل الصور بالتوازي إلى مجلد مؤقت مع إيقاف/استئناف، ثم التحقق من سلامتها وترتيبها.
 * 3) توليد index.html وإضافة الفصل (والمانهوا بغلافها ونبذتها إن لزم) إلى المكتبة.
 * الفصول تُحمَّل واحداً بعد الآخر، وخدمة أمامية (DownloadService) تُبقي التحميل شغالاً وتعرض إشعار التقدم.
 */
object DownloadCenter {

    private const val MAX_ACTIVE = 1
    private const val PARALLEL = 4
    private const val EXTRACT_TIMEOUT_MS = 120_000L
    private const val FULL_TIMEOUT_MS = 150_000L
    private const val SCAN_TIMEOUT_MS = 45_000L

    private lateinit var appContext: Context
    private lateinit var repository: MangaRepository
    private lateinit var storage: FileStorageManager
    private var initialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _tasks = MutableStateFlow<List<DlTask>>(emptyList())
    val tasks: StateFlow<List<DlTask>> = _tasks.asStateFlow()

    /** اسم المانهوا التي تُجمع فصولها الآن (قبل بدء التحميل)، أو null */
    private val _collecting = MutableStateFlow<String?>(null)
    val collecting: StateFlow<String?> = _collecting.asStateFlow()

    /** رسائل قصيرة للمستخدم (تُعرض كإشعار منبثق داخل المتصفح) */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** آخر أحداث التحميل للتشخيص (تُضمَّن في «نسخ تقرير الفحص») */
    private val _report = MutableStateFlow("")
    val report: StateFlow<String> = _report.asStateFlow()

    // تُستخدم من الخيط الرئيسي فقط
    private val jobs = HashMap<String, Job>()
    private val urlsCache = HashMap<String, List<String>>()
    private val metaCache = HashMap<String, ScanResult>()
    private val metaFailures = HashMap<String, Int>()
    private val importMutex = Mutex()
    private var host: WeakReference<Activity>? = null

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        repository = MangaRepository(appContext)
        storage = FileStorageManager(appContext)
        initialized = true
    }

    /** النشاط الذي يستضيف صفحة الفحص المخفية عندما يكون التطبيق ظاهراً */
    fun setHost(activity: Activity?) {
        host = activity?.let { WeakReference(it) }
    }

    private fun log(message: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + message
        _report.update { old ->
            (old.lines().filter { it.isNotBlank() } + line).takeLast(40).joinToString("\n")
        }
    }

    private fun say(message: String) {
        _messages.tryEmit(message)
    }

    private fun isActive(status: DlStatus): Boolean =
        status == DlStatus.QUEUED || status == DlStatus.EXTRACTING || status == DlStatus.DOWNLOADING

    // ───────────────────────── واجهة التحكم ─────────────────────────

    fun enqueue(requests: List<DlRequest>) {
        if (!initialized) return
        val all = _tasks.value
        // عند بدء دفعة جديدة والطابور فارغ نمسح الفصول المكتملة من الدفعة السابقة
        val base = if (all.none { isActive(it.status) }) all.filter { it.status != DlStatus.DONE } else all
        val fresh = requests
            .filter { r ->
                base.none {
                    it.key == r.chapterUrl && it.status != DlStatus.DONE && it.status != DlStatus.FAILED
                }
            }
            .map {
                DlTask(
                    key = it.chapterUrl,
                    mangaTitle = it.mangaTitle.trim(),
                    mangaDescription = it.mangaDescription,
                    coverUrl = it.coverUrl,
                    chapterTitle = it.chapterTitle,
                    mangaUrl = it.mangaUrl
                )
            }
        if (fresh.isEmpty()) return
        requests.forEach { r ->
            val known = r.imageUrls
            if (!known.isNullOrEmpty() && fresh.any { it.key == r.chapterUrl }) urlsCache[r.chapterUrl] = known
        }
        _tasks.value = base.filter { old -> fresh.none { it.key == old.key } } + fresh
        pump()
    }

    /**
     * تحميل كل فصول مانهوا من صفحتها: يجمع الفصول من كل صفحات الترقيم (ويتجاهل الأعمال المشابهة)،
     * ثم يضيف غير الموجود منها في المكتبة إلى الطابور من الأقدم إلى الأحدث.
     * [seed] هو ما فُحص في الصفحة المفتوحة أصلاً، و[nameOverride] اسم المانهوا الذي اختاره المستخدم.
     */
    fun downloadWholeManga(pageUrl: String, seed: ScanResult?, nameOverride: String?) {
        if (!initialized) return
        ensureService()
        scope.launch {
            val label = nameOverride?.takeIf { it.isNotBlank() } ?: seed?.title ?: "المانهوا"
            _collecting.value = label
            log("جمع فصول $label من $pageUrl")
            try {
                val chapters = LinkedHashMap<String, ScanChapter>()
                seed?.chapters?.forEach { chapters[it.url] = it }
                var title = seed?.title.orEmpty()
                var cover = seed?.cover
                var description = seed?.description.orEmpty()

                val full = collectAllPages(pageUrl)
                if (full != null) {
                    full.chapters.forEach { if (!chapters.containsKey(it.url)) chapters[it.url] = it }
                    if (title.isBlank()) title = full.title
                    if (cover == null) cover = full.cover
                    if (description.isBlank()) description = full.description
                }

                val name = nameOverride?.takeIf { it.isNotBlank() } ?: title
                if (chapters.isEmpty() || name.isBlank()) {
                    say("لم أجد فصولاً في هذه الصفحة")
                    return@launch
                }
                val have = repository.downloadedChapterKeys(name)
                val ordered = chapters.values.withIndex()
                    .sortedWith(compareBy<IndexedValue<ScanChapter>>({ chapterNumber(it.value.title) }, { it.index }))
                    .map { it.value }
                val requests = ordered
                    .filter { normalizeTitleKey(it.title) !in have }
                    .map {
                        DlRequest(
                            mangaTitle = name,
                            mangaDescription = description,
                            coverUrl = cover,
                            chapterTitle = it.title,
                            chapterUrl = it.url.substringBefore('#'),
                            mangaUrl = pageUrl
                        )
                    }
                log("وُجد ${chapters.size} فصل، منها ${requests.size} غير موجود في المكتبة")
                if (requests.isEmpty()) {
                    say("كل الفصول (${chapters.size}) موجودة في المكتبة")
                } else {
                    enqueue(requests)
                    say("بدأ تحميل ${requests.size} فصل من ${chapters.size}")
                }
            } finally {
                _collecting.value = null
            }
        }
    }

    fun pause(key: String) {
        jobs[key]?.cancel()
        update(key) {
            if (isActive(status)) copy(status = DlStatus.PAUSED) else this
        }
        pump()
    }

    /** يستأنف فصلاً متوقفاً أو فاشلاً: الصور المحمّلة سابقاً لا تُحمَّل من جديد */
    fun resume(key: String) {
        update(key) {
            if (status == DlStatus.PAUSED || status == DlStatus.FAILED) copy(status = DlStatus.QUEUED, error = null) else this
        }
        pump()
    }

    fun cancel(key: String) {
        jobs[key]?.cancel()
        jobs.remove(key)
        urlsCache.remove(key)
        _tasks.update { list -> list.filterNot { it.key == key } }
        scope.launch(Dispatchers.IO) { stagingDir(key).deleteRecursively() }
        pump()
    }

    fun pauseAll() {
        _tasks.value.map { it.key }.forEach { pause(it) }
    }

    fun resumeAll() {
        _tasks.value.map { it.key }.forEach { resume(it) }
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.status != DlStatus.DONE } }
    }

    /** مفاتيح (موحّدة) لعناوين الفصول الموجودة في المكتبة لمانهوا بهذا الاسم */
    suspend fun downloadedChapterKeys(mangaTitle: String): Set<String> =
        if (!initialized || mangaTitle.isBlank()) emptySet() else repository.downloadedChapterKeys(mangaTitle)

    fun keyOf(title: String): String = normalizeTitleKey(title)

    private fun chapterNumber(title: String): Double {
        val m = Regex("(\\d+(?:[.\\-]\\d+)?)").find(title) ?: return Double.MAX_VALUE
        return m.value.replace('-', '.').toDoubleOrNull() ?: Double.MAX_VALUE
    }

    // ───────────────────────── الجدولة ─────────────────────────

    private fun update(key: String, block: DlTask.() -> DlTask) {
        _tasks.update { list -> list.map { if (it.key == key) it.block() else it } }
    }

    private fun current(key: String): DlTask? = _tasks.value.firstOrNull { it.key == key }

    private fun fail(key: String, message: String) {
        log("فشل ${current(key)?.chapterTitle}: $message")
        update(key) { copy(status = DlStatus.FAILED, error = message) }
    }

    /** يبدأ الخدمة الأمامية التي تُبقي التحميل شغالاً في الخلفية وتعرض الإشعار */
    private fun ensureService() {
        if (!initialized || DownloadService.running) return
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, DownloadService::class.java))
        } catch (e: Exception) {
            log("تعذّر بدء خدمة التحميل: ${e.message}")
        }
    }

    private fun pump() {
        if (!initialized) return
        val tasks = _tasks.value
        if (tasks.any { isActive(it.status) }) ensureService()
        val running = tasks.count { it.status == DlStatus.EXTRACTING || it.status == DlStatus.DOWNLOADING }
        var slots = MAX_ACTIVE - running
        for (task in tasks) {
            if (slots <= 0) break
            if (task.status == DlStatus.QUEUED && jobs[task.key]?.isActive != true) {
                start(task.key)
                slots--
            }
        }
    }

    private fun start(key: String) {
        update(key) {
            copy(
                status = if (urlsCache.containsKey(key)) DlStatus.DOWNLOADING else DlStatus.EXTRACTING,
                error = null
            )
        }
        val job = scope.launch {
            try {
                run(key)
            } catch (e: TimeoutCancellationException) {
                fail(key, "انتهت مهلة فحص الفصل")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(key, e.message ?: "فشل التحميل")
            } finally {
                if (jobs[key] === coroutineContext[Job]) jobs.remove(key)
                pump()
            }
        }
        jobs[key] = job
    }

    // ───────────────────────── تنفيذ مهمة فصل ─────────────────────────

    private fun hashOf(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)

    private fun stagingDir(key: String): File = storage.downloadsStagingDir(hashOf(key))

    private suspend fun run(key: String) {
        val task = current(key) ?: return
        val staging = stagingDir(key)

        var found = urlsCache[key]
        if (found == null) {
            update(key) { copy(status = DlStatus.EXTRACTING, error = null) }
            found = extractImageUrls(key)
            if (found.isEmpty()) {
                fail(key, "لم أجد صور الفصل. افتح الفصل في المتصفح مرة واحدة ثم أعد المحاولة")
                return
            }
            urlsCache[key] = found
        }
        val urls: List<String> = found

        val already = withContext(Dispatchers.IO) { countImages(staging) }
        val startDone = already.coerceAtMost(urls.size)
        update(key) { copy(status = DlStatus.DOWNLOADING, total = urls.size, done = startDone) }

        downloadImages(key, urls, staging)
        // تأكد أن كل الصور موجودة بالترتيب وسليمة، وأعد تحميل التالف مرة واحدة
        verifyAndRepair(key, urls, staging)

        withContext(Dispatchers.IO) { writeHtml(staging, task.chapterTitle) }

        importMutex.withLock {
            val existing = repository.findMangaByTitle(task.mangaTitle)
            val needCover = existing == null || existing.coverPath == null
            val needDesc = existing == null || existing.description.isBlank()
            var coverUrl = task.coverUrl
            var description = task.mangaDescription
            if (((needCover && coverUrl == null) || (needDesc && description.isBlank())) && task.mangaUrl != null) {
                val meta = metaFor(task.mangaUrl)
                if (coverUrl == null) coverUrl = meta?.cover
                if (description.isBlank()) description = meta?.description.orEmpty()
            }
            val cover = if (needCover && coverUrl != null) {
                fetchCover(coverUrl, task.mangaUrl ?: task.key, task.mangaTitle)
            } else null
            log("إضافة ${task.mangaTitle} / ${task.chapterTitle}: غلاف=${cover != null}، طول النبذة=${description.length}")
            repository.importDownloadedChapter(
                mangaTitle = task.mangaTitle,
                description = description,
                coverFile = cover,
                chapterTitle = task.chapterTitle,
                stagingDir = staging,
                dirName = "dl_" + hashOf(key)
            )
        }
        urlsCache.remove(key)
        update(key) { copy(status = DlStatus.DONE, done = urls.size, total = urls.size, error = null) }
    }

    // ───────────────────────── الصفحات المخفية (فحص الفصول والمانهوا) ─────────────────────────

    /**
     * يفتح [url] في WebView مخفي، وبعد اكتمال التحميل بـ[startDelayMs] ينفّذ [script]
     * وينتظر أن يستدعي السكربت AbdoDl.result(json)، ثم يُعيد نص JSON.
     * إن كان التطبيق ظاهراً يُضاف المتصفح المخفي إلى النافذة (شبه شفاف ولا يلتقط اللمس)،
     * وإلا (التحميل في الخلفية) يعمل بلا نافذة بمقاس شاشة افتراضي.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runInHiddenPage(url: String, script: String, timeoutMs: Long, startDelayMs: Long): String {
        return withTimeout(timeoutMs) {
            suspendCancellableCoroutine<String> { cont ->
                val finished = AtomicBoolean(false)
                val hostActivity: Activity? = host?.get()?.takeIf {
                    !it.isFinishing && !it.isDestroyed && it.window.decorView.windowVisibility == View.VISIBLE
                }
                val dm = appContext.resources.displayMetrics
                val root: ViewGroup? = hostActivity?.window?.decorView as? ViewGroup
                val wv: WebView = if (hostActivity != null) {
                    object : WebView(hostActivity) {
                        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
                    }
                } else {
                    WebView(appContext)
                }

                fun cleanup() {
                    mainHandler.post {
                        try {
                            root?.removeView(wv)
                            wv.stopLoading()
                            wv.destroy()
                        } catch (_: Exception) {
                        }
                    }
                }

                fun deliver(result: Result<String>) {
                    if (!finished.compareAndSet(false, true)) return
                    cleanup()
                    if (!cont.isActive) return
                    result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }

                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.addJavascriptInterface(DlJsBridge { json -> deliver(Result.success(json)) }, "AbdoDl")

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String?) {
                        mainHandler.postDelayed({
                            if (!finished.get()) view.evaluateJavascript(script, null)
                        }, startDelayMs)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            deliver(Result.failure(IOException("تعذّر فتح الصفحة (تحقق من الاتصال)")))
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        val code = errorResponse?.statusCode ?: 0
                        if (request?.isForMainFrame == true && code >= 400) {
                            deliver(Result.failure(IOException("رفض الموقع فتح الصفحة ($code). افتح الموقع في المتصفح ثم أعد المحاولة")))
                        }
                    }
                }

                if (root != null) {
                    wv.alpha = 0.01f
                    root.addView(wv, FrameLayout.LayoutParams(dm.widthPixels, dm.heightPixels))
                } else {
                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, dm.widthPixels, dm.heightPixels)
                }
                cont.invokeOnCancellation { finished.set(true); cleanup() }
                wv.loadUrl(url)
            }
        }
    }

    private suspend fun extractImageUrls(chapterUrl: String): List<String> {
        val json = runInHiddenPage(chapterUrl, SiteScripts.COLLECT, EXTRACT_TIMEOUT_MS, 0)
        val arr = JSONObject(json).getJSONArray("urls")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /** يجمع كل الفصول عبر صفحات الترقيم: بالنقر على أرقام الصفحات، أو بزيارة روابطها إن كانت روابط حقيقية */
    private suspend fun collectAllPages(pageUrl: String): ScanResult? {
        val first = try {
            ScanResult.fromJson(runInHiddenPage(pageUrl, SiteScripts.FULL, FULL_TIMEOUT_MS, 2500))
        } catch (e: TimeoutCancellationException) {
            log("انتهت مهلة جمع صفحات الفصول")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("فشل جمع صفحات الفصول: ${e.message}")
            null
        } ?: return null
        log("جمع الصفحات: ${first.chapters.size} فصل، روابط ترقيم=${first.pageUrls.size}")

        val merged = LinkedHashMap<String, ScanChapter>()
        first.chapters.forEach { merged[it.url] = it }
        val visited = hashSetOf(pageUrl)
        val queue = ArrayDeque(first.pageUrls)
        var guard = 0
        while (queue.isNotEmpty() && guard < 40) {
            val next = queue.removeFirst()
            if (!visited.add(next)) continue
            guard++
            val page = try {
                ScanResult.fromJson(runInHiddenPage(next, SiteScripts.SCAN_HIDDEN, SCAN_TIMEOUT_MS, 2000))
            } catch (e: TimeoutCancellationException) {
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            page.chapters.forEach { if (!merged.containsKey(it.url)) merged[it.url] = it }
            page.pageUrls.forEach { if (it !in visited) queue.addLast(it) }
        }
        return first.copy(chapters = merged.values.toList())
    }

    /** غلاف ونبذة المانهوا من صفحتها (فحص في صفحة مخفية، وإلا وسوم og: عبر HTTP) */
    private suspend fun metaFor(mangaUrl: String): ScanResult? {
        metaCache[mangaUrl]?.let { return it }
        if ((metaFailures[mangaUrl] ?: 0) >= 2) return null

        var out: ScanResult? = try {
            ScanResult.fromJson(runInHiddenPage(mangaUrl, SiteScripts.SCAN_HIDDEN, SCAN_TIMEOUT_MS, 2500))
        } catch (e: TimeoutCancellationException) {
            log("انتهت مهلة فحص صفحة المانهوا")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("فشل فحص صفحة المانهوا: ${e.message}")
            null
        }
        if (out == null || (out.cover == null && out.description.isBlank())) {
            val (image, desc) = fetchMangaMeta(mangaUrl)
            if (image != null || desc != null) {
                out = ScanResult(
                    url = mangaUrl,
                    title = out?.title.orEmpty(),
                    cover = out?.cover ?: image,
                    description = out?.description?.takeIf { it.isNotBlank() } ?: desc.orEmpty(),
                    chapters = emptyList(),
                    pageUrls = emptyList(),
                    diagnostics = ""
                )
            }
        }
        log("بيانات المانهوا: غلاف=${out?.cover != null}، طول النبذة=${out?.description?.length ?: 0}")
        if (out != null && (out.cover != null || out.description.isNotBlank())) {
            metaCache[mangaUrl] = out
        } else {
            metaFailures[mangaUrl] = (metaFailures[mangaUrl] ?: 0) + 1
        }
        return out
    }

    // ───────────────────────── المرحلة 2: تحميل الصور ─────────────────────────

    private fun pad(index: Int): String = (index + 1).toString().padStart(3, '0')

    private fun isImageFile(f: File): Boolean =
        f.isFile && !f.name.endsWith(".part") && f.name != "index.html"

    private fun countImages(dir: File): Int = dir.listFiles()?.count { isImageFile(it) } ?: 0

    private fun existingImage(dir: File, index: Int): File? =
        dir.listFiles()?.firstOrNull { isImageFile(it) && it.name.startsWith(pad(index) + ".") }

    private suspend fun downloadImages(key: String, urls: List<String>, dir: File) {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val counter = AtomicInteger(0)
            val semaphore = Semaphore(PARALLEL)
            coroutineScope {
                urls.mapIndexed { index, url ->
                    async {
                        semaphore.withPermit {
                            if (existingImage(dir, index) == null) {
                                downloadOne(url, dir, index, referer = key)
                            }
                            val finishedCount = counter.incrementAndGet()
                            update(key) { copy(done = finishedCount) }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun openConnection(url: String, referer: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(appContext))
        conn.setRequestProperty("Referer", referer)
        conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
        return conn
    }

    private fun extensionFor(contentType: String?, url: String): String {
        val type = contentType?.lowercase().orEmpty()
        return when {
            type.contains("jpeg") || type.contains("jpg") -> "jpg"
            type.contains("png") -> "png"
            type.contains("webp") -> "webp"
            type.contains("gif") -> "gif"
            type.contains("avif") -> "avif"
            else -> {
                val fromUrl = url.substringBefore('?').substringAfterLast('.', "").lowercase()
                if (fromUrl in setOf("jpg", "jpeg", "png", "webp", "gif", "avif")) {
                    if (fromUrl == "jpeg") "jpg" else fromUrl
                } else "jpg"
            }
        }
    }

    /** يحمّل ملفاً إلى [part] ويُعيد الامتداد المناسب. يرمي IOException عند أي فشل. */
    private fun fetchTo(url: String, referer: String, part: File): String {
        val conn = openConnection(url, referer)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val type = conn.contentType
            if (type != null && type.lowercase().startsWith("text/")) throw IOException("الموقع أعاد صفحة بدل صورة")
            conn.inputStream.use { input -> part.outputStream().use { out -> input.copyTo(out) } }
            if (part.length() == 0L) throw IOException("ملف فارغ")
            return extensionFor(type, url)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadOne(url: String, dir: File, index: Int, referer: String) {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            val part = File(dir, pad(index) + ".part")
            try {
                val ext = fetchTo(url, referer, part)
                if (!part.renameTo(File(dir, pad(index) + "." + ext))) throw IOException("تعذّر حفظ الصورة")
                return
            } catch (e: IOException) {
                lastError = e
                part.delete()
                delay(700L * (attempt + 1))
            }
        }
        throw IOException("فشل تحميل صورة ${index + 1}: ${lastError?.message}")
    }

    private fun isValidImage(file: File?): Boolean {
        if (file == null || !file.exists() || file.length() == 0L) return false
        return if (file.extension.lowercase() in setOf("jpg", "png", "webp", "gif")) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            opts.outWidth > 0 && opts.outHeight > 0
        } else true
    }

    /** يتحقق أن كل صورة (1..N) موجودة وتُفتح، ويعيد تحميل التالف مرة واحدة، وإلا يفشل الفصل */
    private suspend fun verifyAndRepair(key: String, urls: List<String>, dir: File) {
        withContext(Dispatchers.IO) {
            for (round in 0..1) {
                val bad = urls.indices.filter { !isValidImage(existingImage(dir, it)) }
                if (bad.isEmpty()) return@withContext
                if (round == 1) throw IOException("الصورة رقم ${bad.first() + 1} تالفة أو ناقصة")
                bad.forEach { existingImage(dir, it)?.delete() }
                val semaphore = Semaphore(PARALLEL)
                coroutineScope {
                    bad.map { i ->
                        async { semaphore.withPermit { downloadOne(urls[i], dir, i, referer = key) } }
                    }.awaitAll()
                }
            }
        }
    }

    private fun metaContent(html: String, prop: String): String? {
        val flags = setOf(RegexOption.IGNORE_CASE)
        val first = Regex("<meta[^>]+(?:property|name)=[\"']$prop[\"'][^>]*content=[\"']([^\"']*)[\"']", flags)
        val second = Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']$prop[\"']", flags)
        val raw = first.find(html)?.groupValues?.get(1) ?: second.find(html)?.groupValues?.get(1)
        return raw?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#039;", "'")
            ?.replace("&#39;", "'")?.trim()?.ifBlank { null }
    }

    /** يجلب غلاف المانهوا ونبذتها من وسوم og: في صفحتها. أي فشل يُعيد (null, null) بصمت. */
    private suspend fun fetchMangaMeta(mangaUrl: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(mangaUrl, mangaUrl)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            try {
                if (conn.responseCode !in 200..299) return@withContext null to null
                val html = conn.inputStream.bufferedReader().use { it.readText() }.take(400_000)
                val image = metaContent(html, "og:image")?.let {
                    try { URL(URL(mangaUrl), it).toString() } catch (e: Exception) { null }
                }
                val description = metaContent(html, "og:description") ?: metaContent(html, "description")
                image to description?.take(800)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null to null
        }
    }

    // ───────────────────────── المرحلة 3: HTML والغلاف ─────────────────────────

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** يولّد index.html: صور متتالية بعرض الشاشة، مع أبعاد كل صورة لمنع القفزات أثناء التحميل */
    private fun writeHtml(dir: File, title: String) {
        val images = dir.listFiles()?.filter { isImageFile(it) }?.sortedBy { it.name } ?: emptyList()
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html dir=\"rtl\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>").append(escapeHtml(title)).append("</title>")
        sb.append("<style>html,body{margin:0;padding:0;background:#000}")
        sb.append("img{display:block;width:100%;height:auto;margin:0;padding:0;border:0}</style>")
        sb.append("</head><body>")
        for (img in images) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(img.absolutePath, opts)
            sb.append("<img src=\"").append(img.name).append("\"")
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                sb.append(" width=\"").append(opts.outWidth).append("\" height=\"").append(opts.outHeight).append("\"")
            }
            sb.append(" decoding=\"async\">")
        }
        sb.append("</body></html>")
        File(dir, "index.html").writeText(sb.toString())
    }

    private suspend fun fetchCover(coverUrl: String, referer: String, name: String): File? =
        withContext(Dispatchers.IO) {
            try {
                val base = "cover_" + hashOf(name)
                val part = File(appContext.cacheDir, "$base.part")
                val ext = fetchTo(coverUrl, referer, part)
                val dest = File(appContext.cacheDir, "$base.$ext")
                if (dest.exists()) dest.delete()
                if (part.renameTo(dest)) dest else null
            } catch (e: Exception) {
                log("فشل تحميل الغلاف: ${e.message}")
                null
            }
        }
}
