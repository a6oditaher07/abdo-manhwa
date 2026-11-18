package com.abdo.manhwa.ui.reader

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
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

    LaunchedEffect(mangaId, chapterId) {
        loadError = false
        viewModel.loadChapter(mangaId, chapterId)
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

    // ───── حفظ تلقائي لموضع القراءة كل بضع ثوانٍ ─────
    LaunchedEffect(state.chapter?.id) {
        val wv = webViewRef
        if (wv == null || state.chapter == null) return@LaunchedEffect
        while (true) {
            delay(4000)
            readScrollPercent(wv) { percent ->
                if (percent != null) {
                    viewModel.saveProgress(percent)
                    if (percent >= 0.97f && !state.showNextChapterCard) {
                        viewModel.onReachedEnd()
                    }
                }
            }
        }
    }

    // ───── حفظ عند الخروج من الشاشة ─────
    DisposableEffect(state.chapter?.id) {
        onDispose {
            val wv = webViewRef
            if (wv != null) {
                readScrollPercent(wv) { percent ->
                    if (percent != null) viewModel.saveProgress(percent)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        when {
            state.fileMissing -> ReaderErrorContent(onBack = onBack)
            state.chapter != null -> {
                val chapterPath = state.chapter!!.filePath
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        ManhwaWebView(ctx, onCenterTap = { viewModel.toggleControls() }).apply {
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
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    val targetPercent = state.chapter?.scrollPercent ?: 0f
                                    if (targetPercent > 0f) {
                                        view.postDelayed({
                                            view.evaluateJavascript(
                                                "window.scrollTo(0, Math.floor((document.scrollingElement.scrollHeight - window.innerHeight) * $targetPercent));",
                                                null
                                            )
                                        }, 250)
                                    }
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
                            loadUrl("file://" + File(chapterPath).absolutePath)
                        }
                    },
                    update = { wv ->
                        if (!wv.url.orEmpty().endsWith(File(chapterPath).name)) {
                            wv.loadUrl("file://" + File(chapterPath).absolutePath)
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

        // ───── الشريط العلوي ─────
        AnimatedVisibility(
            visible = state.controlsVisible,
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.manga?.title ?: "", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(state.chapter?.title ?: "", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ───── الشريط السفلي ─────
        AnimatedVisibility(
            visible = state.controlsVisible,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.BrightnessLow, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                    Slider(
                        value = if (brightness < 0f) 0.5f else brightness,
                        onValueChange = { viewModel.setBrightness(it) },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
                    )
                    Icon(Icons.Filled.BrightnessHigh, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        scope.launch {
                            viewModel.previousChapterId()?.let { onNavigateToChapter(it) }
                        }
                    }, enabled = state.hasPrevious) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "الفصل السابق", tint = if (state.hasPrevious) Color.White else Color.White.copy(alpha = 0.3f))
                    }

                    IconButton(onClick = {
                        scope.launch {
                            chapterListData = viewModel.chaptersForQuickJump()
                            showChapterList = true
                        }
                    }) {
                        Icon(Icons.Filled.List, contentDescription = "قائمة الفصول", tint = Color.White)
                    }

                    IconButton(onClick = { viewModel.setEyeComfort(!eyeComfort) }) {
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = "وضع راحة العين",
                            tint = if (eyeComfort) AccentPrimary else Color.White
                        )
                    }

                    IconButton(onClick = { viewModel.setKeepScreenOn(!keepScreenOn) }) {
                        Icon(
                            if (keepScreenOn) Icons.Filled.LightMode else Icons.Filled.LightMode,
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
                        scope.launch {
                            viewModel.nextChapterId()?.let { onNavigateToChapter(it) }
                        }
                    }, enabled = state.hasNext) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "الفصل التالي", tint = if (state.hasNext) Color.White else Color.White.copy(alpha = 0.3f))
                    }
                }
            }
        }

        // ───── بطاقة "الفصل التالي" عند نهاية الفصل ─────
        if (state.showNextChapterCard && state.hasNext) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1726)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("انتهيت من هذا الفصل 🎉", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    viewModel.nextChapterId()?.let { onNavigateToChapter(it) }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("الانتقال إلى الفصل التالي")
                        }
                    }
                }
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
                    onNavigateToChapter(it)
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

/** يقرأ نسبة التمرير الحالية من صفحة الـ WebView عبر JavaScript */
private fun readScrollPercent(webView: WebView, onResult: (Float?) -> Unit) {
    webView.evaluateJavascript(
        "(function(){try{var d=document.scrollingElement;var max=d.scrollHeight-window.innerHeight;if(max<=0)return 1;return Math.min(1, Math.max(0, d.scrollTop/max));}catch(e){return -1}})();"
    ) { result ->
        val value = result?.toFloatOrNull()
        onResult(if (value == null || value < 0f) null else value)
    }
}
