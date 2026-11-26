package com.abdo.manhwa.ui.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.abdo.manhwa.download.DlRequest
import com.abdo.manhwa.download.DlStatus
import com.abdo.manhwa.download.DlTask
import com.abdo.manhwa.download.DownloadCenter
import com.abdo.manhwa.download.ScanChapter
import com.abdo.manhwa.download.ScanResult
import com.abdo.manhwa.download.SiteScripts
import com.abdo.manhwa.ui.theme.AccentPrimary
import com.abdo.manhwa.ui.theme.BackgroundDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun normalizeUrl(input: String): String {
    val t = input.trim()
    if (t.isEmpty()) return ""
    return if (t.contains("://")) t else "https://$t"
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("abdo_browser", Context.MODE_PRIVATE) }

    var homeUrl by remember { mutableStateOf(prefs.getString("home", "") ?: "") }
    var urlText by remember { mutableStateOf(homeUrl) }
    var progress by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<ScanResult?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showHomeDialog by remember { mutableStateOf(homeUrl.isEmpty()) }

    val tasks by DownloadCenter.tasks.collectAsState()
    val activeCount = tasks.count {
        it.status == DlStatus.QUEUED || it.status == DlStatus.EXTRACTING || it.status == DlStatus.DOWNLOADING
    }

    fun load(raw: String) {
        val url = normalizeUrl(raw)
        if (url.isNotEmpty()) {
            urlText = url
            webView?.loadUrl(url)
        }
    }

    /** يفحص الصفحة الحالية بحثاً عن قائمة فصول (يُستدعى بعد التحميل وبعد تأخير لانتظار المحتوى الديناميكي) */
    fun scanPage(afterMs: Long = 0L) {
        scope.launch {
            if (afterMs > 0) delay(afterMs)
            webView?.evaluateJavascript(SiteScripts.SCAN) { raw ->
                val result = ScanResult.parse(raw)
                if (result != null) scan = result
            }
        }
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, contentDescription = "إغلاق المتصفح", tint = Color.White)
                    }
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { load(urlText) })
                    )
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "تحديث", tint = Color.White)
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "المزيد", tint = Color.White)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("الصفحة الرئيسية") },
                                leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    if (homeUrl.isEmpty()) showHomeDialog = true else load(homeUrl)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("تثبيت هذه الصفحة كرئيسية") },
                                leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    val current = webView?.url ?: urlText
                                    if (current.isNotBlank()) {
                                        homeUrl = current
                                        prefs.edit().putString("home", current).apply()
                                        Toast.makeText(context, "تم تثبيت الصفحة الرئيسية", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("إعادة فحص الصفحة") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    scanPage()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("نسخ تقرير الفحص") },
                                leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    val report = scan?.diagnostics?.ifBlank { null }
                                        ?: "لا توجد نتيجة فحص لهذه الصفحة: ${webView?.url}"
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("abdo-scan", report))
                                    Toast.makeText(context, "تم نسخ تقرير الفحص", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = AccentPrimary
                    )
                }
            }
        },
        floatingActionButton = {
            val chapters = scan?.chapters.orEmpty()
            if (chapters.isNotEmpty() || activeCount > 0) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (chapters.isEmpty()) scanPage()
                        showSheet = true
                    },
                    containerColor = AccentPrimary,
                    icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    text = {
                        Text(
                            if (activeCount > 0) "التحميلات ($activeCount)"
                            else "تحميل الفصول (${chapters.size})"
                        )
                    }
                )
            }
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.setSupportMultipleWindows(false)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            scan = null
                            if (url != null) urlText = url
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            if (url != null) urlText = url
                            canGoBack = view.canGoBack()
                            CookieManager.getInstance().flush()
                            scanPage(900)
                            scanPage(3500)
                        }
                    }
                    webView = this
                    if (homeUrl.isNotEmpty()) loadUrl(homeUrl)
                }
            }
        )
    }

    if (showHomeDialog) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (homeUrl.isNotEmpty()) showHomeDialog = false },
            title = { Text("موقع المانهوا") },
            text = {
                Column {
                    Text("أدخل رابط الموقع الذي تقرأ منه ليُفتح تلقائياً في هذا المتصفح.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = { Text("example.com") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val url = normalizeUrl(input)
                        homeUrl = url
                        prefs.edit().putString("home", url).apply()
                        showHomeDialog = false
                        load(url)
                    }
                ) { Text("فتح وتثبيت") }
            },
            dismissButton = {
                TextButton(onClick = { showHomeDialog = false; if (homeUrl.isEmpty()) onBack() }) { Text("إلغاء") }
            }
        )
    }

    if (showSheet) {
        ChapterSheet(
            scan = scan,
            tasks = tasks,
            onRescan = { scanPage() },
            onDismiss = { showSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    scan: ScanResult?,
    tasks: List<DlTask>,
    onRescan: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val chapters = scan?.chapters.orEmpty()
    val taskByKey = remember(tasks) { tasks.associateBy { it.key } }

    var mangaName by remember(scan?.url) { mutableStateOf(scan?.title.orEmpty()) }
    var selected by remember(scan?.url) { mutableStateOf(setOf<String>()) }
    var downloaded by remember { mutableStateOf(setOf<String>()) }
    val doneCount = tasks.count { it.status == DlStatus.DONE }

    // الفصول الموجودة فعلاً في المكتبة (تُحدَّث عند اكتمال أي تحميل)
    LaunchedEffect(mangaName, doneCount) {
        downloaded = DownloadCenter.downloadedChapterKeys(mangaName)
    }

    fun requestFor(ch: ScanChapter) = DlRequest(
        mangaTitle = mangaName.ifBlank { scan?.title.orEmpty() },
        mangaDescription = scan?.description.orEmpty(),
        coverUrl = scan?.cover,
        chapterTitle = ch.title,
        chapterUrl = ch.url
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {

            if (chapters.isEmpty()) {
                Text("لا توجد فصول في هذه الصفحة بعد.", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onRescan) { Text("إعادة الفحص") }
            }

            OutlinedTextField(
                value = mangaName,
                onValueChange = { mangaName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("اسم المانهوا في المكتبة") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected = chapters.map { it.url }.toSet() }) { Text("تحديد الكل") }
                TextButton(onClick = {
                    selected = chapters
                        .filter { DownloadCenter.keyOf(it.title) !in downloaded && taskByKey[it.url]?.status != DlStatus.DONE }
                        .map { it.url }.toSet()
                }) { Text("غير المحمّلة") }
                TextButton(onClick = { selected = emptySet() }) { Text("مسح") }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { DownloadCenter.pauseAll() }) {
                    Icon(Icons.Filled.Pause, contentDescription = "إيقاف الكل")
                }
                IconButton(onClick = { DownloadCenter.resumeAll() }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "استئناف الكل")
                }
            }

            val others = tasks.filter { t -> t.status != DlStatus.DONE && chapters.none { it.url == t.key } }

            LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 460.dp)) {
                items(others, key = { "other_" + it.key }) { t ->
                    ChapterRow(
                        chapter = ScanChapter(title = t.mangaTitle + " — " + t.chapterTitle, url = t.key),
                        task = t,
                        inLibrary = false,
                        checked = false,
                        onCheckedChange = {},
                        onDownload = {},
                        showCheckbox = false
                    )
                }
                items(chapters, key = { it.url }) { ch ->
                    ChapterRow(
                        chapter = ch,
                        task = taskByKey[ch.url],
                        inLibrary = DownloadCenter.keyOf(ch.title) in downloaded,
                        checked = ch.url in selected,
                        onCheckedChange = { on -> selected = if (on) selected + ch.url else selected - ch.url },
                        onDownload = { DownloadCenter.enqueue(listOf(requestFor(ch))) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    // الأقدم أولاً (القائمة في الموقع من الأحدث للأقدم) لتمتلئ المكتبة بالترتيب
                    val requests = chapters.reversed().filter { it.url in selected }.map { requestFor(it) }
                    DownloadCenter.enqueue(requests)
                    selected = emptySet()
                },
                enabled = selected.isNotEmpty() && mangaName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
            ) {
                Text("تحميل المحدد (${selected.size})")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ScanChapter,
    task: DlTask?,
    inLibrary: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDownload: () -> Unit,
    showCheckbox: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (showCheckbox) {
                Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            } else {
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(chapter.title, style = MaterialTheme.typography.bodyLarge)
                val statusText: String? = if (task == null) {
                    if (inLibrary) "موجود في المكتبة" else null
                } else {
                    when (task.status) {
                        DlStatus.QUEUED -> "في الانتظار"
                        DlStatus.EXTRACTING -> "جارٍ فحص الفصل…"
                        DlStatus.DOWNLOADING -> "جارٍ التحميل ${(task.percent * 100).toInt()}% (${task.done}/${task.total})"
                        DlStatus.PAUSED -> "متوقف ${(task.percent * 100).toInt()}%"
                        DlStatus.FAILED -> task.error ?: "فشل التحميل"
                        DlStatus.DONE -> "تمت الإضافة إلى المكتبة"
                    }
                }
                if (statusText != null) {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (task?.status == DlStatus.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (task == null) {
                if (inLibrary) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AccentPrimary)
                } else {
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = "تحميل الفصل", tint = AccentPrimary)
                    }
                }
            } else {
                when (task.status) {
                    DlStatus.QUEUED, DlStatus.EXTRACTING, DlStatus.DOWNLOADING -> {
                        IconButton(onClick = { DownloadCenter.pause(task.key) }) {
                            Icon(Icons.Filled.Pause, contentDescription = "إيقاف مؤقت")
                        }
                        IconButton(onClick = { DownloadCenter.cancel(task.key) }) {
                            Icon(Icons.Filled.Close, contentDescription = "إلغاء")
                        }
                    }
                    DlStatus.PAUSED, DlStatus.FAILED -> {
                        IconButton(onClick = { DownloadCenter.resume(task.key) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "استئناف")
                        }
                        IconButton(onClick = { DownloadCenter.cancel(task.key) }) {
                            Icon(Icons.Filled.Close, contentDescription = "إلغاء")
                        }
                    }
                    DlStatus.DONE -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AccentPrimary)
                }
            }
        }
        if (task != null && (task.status == DlStatus.DOWNLOADING || task.status == DlStatus.PAUSED)) {
            LinearProgressIndicator(
                progress = { task.percent },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                color = AccentPrimary
            )
        }
    }
}
