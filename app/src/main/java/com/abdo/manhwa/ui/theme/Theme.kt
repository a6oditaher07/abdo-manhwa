package com.abdo.manhwa.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Color_OnAccent = androidx.compose.ui.graphics.Color(0xFF1A0B2E)

// التطبيق يعتمد الوضع الداكن افتراضياً دائماً كما طلب المستخدم
private val DarkScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = Color_OnAccent,
    primaryContainer = AccentPrimaryVariant,
    secondary = AccentPrimary,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnBackgroundDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceMuted,
    error = ErrorColor,
    outline = DividerDark
)

@Composable
fun AbdoManhwaTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = view.context
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            window.statusBarColor = BackgroundDark.toArgb()
            window.navigationBarColor = BackgroundDark.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        content = content
    )
}
