package com.abdo.manhwa.download

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    /** رابط صفحة المانهوا (لجلب الغلاف والنبذة تلقائياً إن لم يُعطَ الغلاف) */
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
 * مركز التحميل: طابور فصول، كل فصل يمرّ بثلاث مراحل:
 * 1) فحص صفحة الفصل داخل WebView مخفي لجمع روابط الصور (مع تمرير تلقائي للصور الكسولة).
 * 2) تحميل الصور بالتوازي إلى مجلد مؤقت، مع إمكانية الإيقاف والاستئناف (الصور المكتملة لا تُعاد).
 * 3) توليد index.html وإضافة الفصل (والمانهوا إن لزم) إلى المكتبة.
 */
object DownloadCenter {

    private const val MAX_ACTIVE = 2
    private const val PARALLEL = 4
    private const val EXTRACT_TIMEOUT_MS = 120_000L

    private lateinit var appContext: Context
    private lateinit var repository: MangaRepository
    private lateinit var storage: FileStorageManager
    private var initialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _tasks = MutableStateFlow<List<DlTask>>(emptyList())
    val tasks: StateFlow<List<DlTask>> = _tasks.asStateFlow()

    // تُستخدم من الخيط الرئيسي فقط
    private val jobs = HashMap<String, Job>()
    private val urlsCache = HashMap<String, List<String>>()
    private val importMutex = Mutex()
    private var host: WeakReference<Activity>? = null

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        repository = MangaRepository(appContext)
        storage = FileStorageManager(appContext)
        initialized = true
    }

    /** النشاط الذي يستضيف الـ WebView المخفي لفحص الفصول (يُسجَّل من واجهة التنقل) */
    fun setHost(activity: Activity?) {
        host = activity?.let { WeakReference(it) }
    }

    // ───────────────────────── واجهة التحكم ─────────────────────────

    fun enqueue(requests: List<DlRequest>) {
        val current = _tasks.value
        val fresh = requests
            .filter { r ->
                current.none {
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
        _tasks.value = current.filter { old -> fresh.none { it.key == old.key } } + fresh
        pump()
    }

    fun pause(key: String) {
        jobs[key]?.cancel()
        update(key) {
            if (status == DlStatus.QUEUED || status == DlStatus.EXTRACTING || status == DlStatus.DOWNLOADING) {
                copy(status = DlStatus.PAUSED)
            } else this
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

    // ───────────────────────── الجدولة ─────────────────────────

    private fun update(key: String, block: DlTask.() -> DlTask) {
        _tasks.update { list -> list.map { if (it.key == key) it.block() else it } }
    }

    private fun current(key: String): DlTask? = _tasks.value.firstOrNull { it.key == key }

    private fun fail(key: String, message: String) {
        update(key) { copy(status = DlStatus.FAILED, error = message) }
    }

    private fun pump() {
        if (!initialized) return
        val running = _tasks.value.count { it.status == DlStatus.EXTRACTING || it.status == DlStatus.DOWNLOADING }
        var slots = MAX_ACTIVE - running
        for (task in _tasks.value) {
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
            var cover: File? = null
            var description = task.mangaDescription
            if (existing == null || existing.coverPath == null) {
                var coverUrl = task.coverUrl
                if ((coverUrl == null || description.isBlank()) && task.mangaUrl != null) {
                    val meta = fetchMangaMeta(task.mangaUrl)
                    if (coverUrl == null) coverUrl = meta.first
                    if (description.isBlank()) description = meta.second.orEmpty()
                }
                cover = fetchCover(task.copy(coverUrl = coverUrl))
            }
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

    // ───────────────────────── المرحلة 1: جمع روابط الصور ─────────────────────────

    private suspend fun awaitHost(): Activity {
        while (true) {
            host?.get()?.let { if (!it.isFinishing) return it }
            delay(500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractImageUrls(chapterUrl: String): List<String> {
        return withTimeout(EXTRACT_TIMEOUT_MS) {
            val activity = awaitHost()
            suspendCancellableCoroutine<List<String>> { cont ->
                val root = activity.window.decorView as ViewGroup
                val finished = AtomicBoolean(false)

                // WebView شبه شفاف بحجم الشاشة (ليحسب الموقع نافذة عرض حقيقية) لكنه لا يلتقط اللمس
                val wv = object : WebView(activity) {
                    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
                }
                fun cleanup() {
                    mainHandler.post {
                        try {
                            root.removeView(wv)
                            wv.stopLoading()
                            wv.destroy()
                        } catch (_: Exception) {
                        }
                    }
                }
                fun deliver(result: Result<List<String>>) {
                    if (!finished.compareAndSet(false, true)) return
                    cleanup()
                    if (!cont.isActive) return
                    result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }

                wv.alpha = 0.01f
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                wv.addJavascriptInterface(DlJsBridge { json ->
                    try {
                        val arr = JSONObject(json).getJSONArray("urls")
                        deliver(Result.success((0 until arr.length()).map { arr.getString(it) }))
                    } catch (e: Exception) {
                        deliver(Result.failure(IOException("تعذّر قراءة صور الفصل")))
                    }
                }, "AbdoDl")

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(SiteScripts.COLLECT, null)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            deliver(Result.failure(IOException("تعذّر فتح صفحة الفصل (تحقق من الاتصال)")))
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                            deliver(
                                Result.failure(
                                    IOException("رفض الموقع فتح الفصل (${errorResponse?.statusCode}). افتح الموقع في المتصفح ثم أعد المحاولة")
                                )
                            )
                        }
                    }
                }

                val dm = activity.resources.displayMetrics
                root.addView(wv, FrameLayout.LayoutParams(dm.widthPixels, dm.heightPixels))
                cont.invokeOnCancellation { finished.set(true); cleanup() }
                wv.loadUrl(chapterUrl)
            }
        }
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

    private suspend fun fetchCover(task: DlTask): File? = withContext(Dispatchers.IO) {
        val url = task.coverUrl ?: return@withContext null
        try {
            val part = File(appContext.cacheDir, "cover_" + hashOf(task.mangaTitle) + ".part")
            val ext = fetchTo(url, task.key, part)
            val dest = File(appContext.cacheDir, "cover_" + hashOf(task.mangaTitle) + "." + ext)
            if (part.renameTo(dest)) dest else null
        } catch (e: Exception) {
            null
        }
    }
}
