package com.abdo.manhwa.ui.reader

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.abdo.manhwa.data.local.entity.ChapterEntity
import com.abdo.manhwa.ui.theme.AccentPrimary
import com.abdo.manhwa.util.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    mangaId: Long,
    chapterId: Long,
    onBack: () -> Unit,
    onNavigateToChapter: (Long) -> Unit
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsState()
    val brightness by viewModel.brightness.collectAsState(initial = -1f)
    val eyeComfort by viewModel.eyeComfort.collectAsState(initial = false)
    val keepScreenOn by viewModel.keepScreenOn.collectAsState(initial = true)
    val orientationLock by viewModel.orientationLock.collectAsState(initial = 0)

    var webViewRef by remember { mutableStateOf<ManhwaWebView?>(null) }
    var showChapterList by remember { mutableStateOf(false) }
    var chapterListData by remember { mutableStateOf<List<ChapterEntity>>(emptyList()) }
    var loadError by remember { mutableStateOf(false) }

    // ───── قفل أدوات التحكم: يخفي كل الأشرطة والأزرار ويمنع ظهورها باللمس العادي ─────
    var isLocked by remember { mutableStateOf(false) }
    var showUnlockHint by remember { mutableStateOf(false) }

    // ───── شريط تمرير الموضع داخل الفصل الظاهر (نسبة مئوية) ─────
    var sliderPercent by remember { mutableStateOf(0f) }
    var isDraggingSlider by remember { mutableStateOf(false) }

    val barsVisible = state.controlsVisible && !isLocked

    // لون التمييز يُمرَّر لصفحة الويب لتلوين الفاصل بين الفصول
    val accentHex = remember { String.format(Locale.US, "#%06X", 0xFFFFFF and AccentPrimary.toArgb()) }

    /** يحمّل الفصل التالي مسبقاً ويضيفه أسفل الفصل الأخير داخل نفس صفحة التمرير */
    fun appendNext(lastChapterId: Long) {
        val wv = webViewRef ?: return
        val next = viewModel.nextExistingChapter(lastChapterId)
        if (next == null) {
            wv.evaluateJavascript("window.__abdoNoMore && window.__abdoNoMore();", null)
        } else {
            val url = Uri.fromFile(File(next.filePath)).toString()
            wv.evaluateJavascript(
                "window.__abdoAppend && window.__abdoAppend(" +
                    "${next.id}, ${JSONObject.quote(next.title)}, ${JSONObject.quote(url)});",
                null
            )
        }
    }

    /**
     * الانتقال إلى فصل: إن كان موجوداً في السلسلة المتصلة نقفز إليه فوراً بلا إعادة تحميل،
     * وإلا نبدأ سلسلة جديدة من ذلك الفصل.
     */
    fun goToChapter(id: Long) {
        loadError = false
        val wv = webViewRef
        if (wv == null) {
            viewModel.loadChapter(mangaId, id)
            return
        }
        wv.evaluateJavascript("window.__abdoGoto ? window.__abdoGoto($id) : false") { result ->
            if (result != "true") viewModel.loadChapter(mangaId, id)
        }
    }

    fun handleCenterTap() {
        if (isLocked) {
            if (showUnlockHint) {
                isLocked = false
                showUnlockHint = false
            } else {
                showUnlockHint = true
                scope.launch {
                    delay(2200)
                    showUnlockHint = false
                }
            }
        } else {
            viewModel.toggleControls()
        }
    }

    val bridge = remember {
        ReaderJsBridge(
            onVisible = { id -> viewModel.onChapterVisible(id) },
            onProgress = { id, percent ->
                if (id == viewModel.state.value.chapter?.id && !isDraggingSlider) sliderPercent = percent
                viewModel.reportProgress(id, percent)
            },
            onFinished = { id -> viewModel.onChapterFinished(id) },
            onNeedNext = { lastId -> appendNext(lastId) },
            chapterPathProvider = { id -> viewModel.chapterPath(id) }
        )
    }

    LaunchedEffect(mangaId, chapterId) {
        viewModel.loadChapter(mangaId, chapterId)
    }

    // عند أي تحميل كامل جديد يرجع شريط الموضع للبداية
    LaunchedEffect(state.loadToken) {
        sliderPercent = 0f
    }

    // ───── الوضع الغامر (Immersive): إخفاء شريط الحالة وشريط التنقل ─────
    DisposableEffect(Unit) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // ───── إبقاء الشاشة مضاءة حسب تفضيل المستخدم ─────
    DisposableEffect(keepScreenOn) {
        val window = activity?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ───── قفل اتجاه الشاشة ─────
    DisposableEffect(orientationLock) {
        activity?.requestedOrientation = when (orientationLock) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // ───── السطوع الخاص بالتطبيق فقط (لا يغيّر سطوع النظام العام) ─────
    DisposableEffect(brightness) {
        val window = activity?.window
        val layoutParams = window?.attributes
        layoutParams?.screenBrightness = if (brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness
        if (layoutParams != null) window.attributes = layoutParams
        onDispose {
            val lp = window?.attributes
            lp?.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            if (lp != null) window?.attributes = lp
        }
    }

    // ───── حفظ دوري لآخر موضع قراءة (احتياط في حال توقّف التمرير قبل انقضاء مهلة الحفظ) ─────
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            viewModel.flushProgress()
        }
    }

    // ───── حفظ عند الخروج من الشاشة ─────
    DisposableEffect(Unit) {
        onDispose { viewModel.flushProgress() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        when {
            state.fileMissing -> ReaderErrorContent(onBack = onBack)
            state.startChapter != null -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        ManhwaWebView(ctx, onCenterTap = { handleCenterTap() }).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            @Suppress("DEPRECATION")
                            settings.allowFileAccessFromFileURLs = true
                            @Suppress("DEPRECATION")
                            settings.allowUniversalAccessFromFileURLs = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            addJavascriptInterface(bridge, "AbdoReader")
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    val start = viewModel.state.value.startChapter ?: return
                                    // فصل أُنهي سابقاً يُفتح من بدايته بدل نهايته
                                    val restore = if (start.scrollPercent >= 0.97f) 0f else start.scrollPercent
                                    view.evaluateJavascript(ReaderRuntime.SCRIPT, null)
                                    view.evaluateJavascript(
                                        "window.__abdoInit(${start.id}, ${JSONObject.quote(start.title)}, " +
                                            "$restore, ${JSONObject.quote(accentHex)});",
                                        null
                                    )
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (request?.isForMainFrame == true) loadError = true
                                }
                            }
                            webViewRef = this
                            tag = state.loadToken
                            loadUrl("file://" + File(state.startChapter!!.filePath).absolutePath)
                        }
                    },
                    update = { wv ->
                        // تحميل جديد كامل فقط عند قفزة إلى فصل خارج السلسلة المتصلة الحالية
                        val start = state.startChapter
                        if (start != null && wv.tag != state.loadToken) {
                            wv.tag = state.loadToken
                            loadError = false
                            wv.loadUrl("file://" + File(start.filePath).absolutePath)
                        }
                    }
                )

                if (loadError) {
                    ReaderErrorContent(onBack = onBack)
                }

                // طبقة وضع راحة العين: فلتر دافئ شفاف لا يعيق اللمس
                if (eyeComfort) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFFF8A00).copy(alpha = 0.12f))
                    )
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentPrimary)
                }
            }
        }

        // ───── الشريط العلوي: رجوع، العنوان، الفصل الحالي، وسهم تخطّي سريع للفصل التالي ─────
        AnimatedVisibility(
            visible = barsVisible,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.manga?.title ?: "", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(state.chapter?.title ?: "", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = {
                    viewModel.nextChapterId()?.let { goToChapter(it) }
                }, enabled = state.hasNext) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "تخطّي إلى الفصل التالي",
                        tint = if (state.hasNext) Color.White else Color.White.copy(alpha = 0.3f)
                    )
                }
            }
        }

        // ───── زر المفضّلة العائم (نجمة) على جانب الشاشة ─────
        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)
        ) {
            val isFavorite = state.manga?.isFavorite == true
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .clickable { viewModel.toggleFavorite() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "المفضّلة",
                    tint = if (isFavorite) Color(0xFFFFC107) else Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "المفضلة",
                    color = if (isFavorite) Color(0xFFFFC107) else Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // ───── الشريط السفلي ─────
        AnimatedVisibility(
            visible = barsVisible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // صف التنقل بين الفصول
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        viewModel.previousChapterId()?.let { goToChapter(it) }
                    }, enabled = state.hasPrevious) {
                        Icon(Icons.Filled.ArrowForward, contentDescription = "الفصل السابق", tint = if (state.hasPrevious) Color.White else Color.White.copy(alpha = 0.3f))
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                scope.launch {
                                    chapterListData = viewModel.chaptersForQuickJump()
                                    showChapterList = true
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            state.chapter?.title ?: "",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1
                        )
                    }

                    IconButton(onClick = {
                        viewModel.nextChapterId()?.let { goToChapter(it) }
                    }, enabled = state.hasNext) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "الفصل التالي", tint = if (state.hasNext) Color.White else Color.White.copy(alpha = 0.3f))
                    }
                }

                // شريط موضع القراءة داخل الفصل
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = sliderPercent,
                        onValueChange = {
                            isDraggingSlider = true
                            sliderPercent = it
                        },
                        onValueChangeFinished = {
                            isDraggingSlider = false
                            val visibleId = state.chapter?.id
                            if (visibleId != null) {
                                webViewRef?.evaluateJavascript(
                                    "window.__abdoSeek && window.__abdoSeek($visibleId, $sliderPercent);",
                                    null
                                )
                            }
                        },
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
                    )
                    Text(
                        "${(sliderPercent * 100).toInt()}%",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(40.dp)
                    )
                }

                // شريط السطوع
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.BrightnessLow, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                    Slider(
                        value = if (brightness < 0f) 0.5f else brightness,
                        onValueChange = { viewModel.setBrightness(it) },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
                    )
                    Icon(Icons.Filled.BrightnessHigh, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                    Text(
                        "${(if (brightness < 0f) 50 else (brightness * 100).toInt())}%",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(40.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // صف الأيقونات: راحة العين، إبقاء الشاشة مضاءة، قفل الاتجاه، قفل أدوات التحكم
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.setEyeComfort(!eyeComfort) }) {
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = "وضع راحة العين",
                            tint = if (eyeComfort) AccentPrimary else Color.White
                        )
                    }

                    IconButton(onClick = { viewModel.setKeepScreenOn(!keepScreenOn) }) {
                        Icon(
                            Icons.Filled.LightMode,
                            contentDescription = "إبقاء الشاشة مضاءة",
                            tint = if (keepScreenOn) AccentPrimary else Color.White.copy(alpha = 0.5f)
                        )
                    }

                    IconButton(onClick = { viewModel.setOrientationLock((orientationLock + 1) % 3) }) {
                        Icon(
                            Icons.Filled.ScreenLockRotation,
                            contentDescription = "قفل اتجاه الشاشة",
                            tint = if (orientationLock != 0) AccentPrimary else Color.White
                        )
                    }

                    IconButton(onClick = {
                        isLocked = true
                        viewModel.setControlsVisible(false)
                    }) {
                        Icon(Icons.Filled.Lock, contentDescription = "قفل أدوات التحكم", tint = Color.White)
                    }
                }
            }
        }

        // ───── تلميح فتح القفل: يظهر عند الضغط على الشاشة وهي مقفلة ─────
        AnimatedVisibility(
            visible = isLocked && showUnlockHint,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Icon(Icons.Filled.LockOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.height(6.dp))
                Text("اضغط مرة أخرى لإلغاء القفل", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showChapterList) {
        ModalBottomSheet(onDismissRequest = { showChapterList = false }) {
            LazyColumnChapters(
                chapters = chapterListData,
                currentChapterId = state.chapter?.id,
                onSelect = {
                    showChapterList = false
                    goToChapter(it)
                }
            )
        }
    }
}

@Composable
private fun LazyColumnChapters(
    chapters: List<ChapterEntity>,
    currentChapterId: Long?,
    onSelect: (Long) -> Unit
) {
    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
        items(chapters, key = { it.id }) { chapter ->
            ListItem(
                headlineContent = { Text(chapter.title, color = if (chapter.id == currentChapterId) AccentPrimary else Color.White) },
                trailingContent = {
                    if (chapter.isRead) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AccentPrimary)
                },
                modifier = Modifier.clickable { onSelect(chapter.id) }
            )
        }
    }
}

@Composable
private fun ReaderErrorContent(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("تعذّر فتح هذا الفصل", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text("الملف تالف أو مفقود", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)) {
                Text("رجوع")
            }
        }
    }
}
