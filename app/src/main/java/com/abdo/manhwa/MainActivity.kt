package com.abdo.manhwa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.abdo.manhwa.ui.navigation.AbdoManhwaNavHost
import com.abdo.manhwa.ui.theme.AbdoManhwaTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // شاشة البداية الأصلية (Android Splash Screen API) قبل تحميل واجهة Compose
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as ManhwaApplication

        setContent {
            AbdoManhwaTheme {
                AbdoManhwaNavHost(
                    repository = app.repository,
                    settingsDataStore = app.settingsDataStore
                )
            }
        }
    }
}
