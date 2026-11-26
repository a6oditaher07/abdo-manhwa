package com.abdo.manhwa.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.abdo.manhwa.data.datastore.SettingsDataStore
import com.abdo.manhwa.data.repository.MangaRepository
import com.abdo.manhwa.download.DownloadCenter
import com.abdo.manhwa.util.findActivity
import com.abdo.manhwa.ui.addmanga.AddMangaScreen
import com.abdo.manhwa.ui.addmanga.AddMangaViewModel
import com.abdo.manhwa.ui.browser.BrowserScreen
import com.abdo.manhwa.ui.common.AppViewModelFactory
import com.abdo.manhwa.ui.detail.DetailScreen
import com.abdo.manhwa.ui.detail.DetailViewModel
import com.abdo.manhwa.ui.library.LibraryScreen
import com.abdo.manhwa.ui.library.LibraryViewModel
import com.abdo.manhwa.ui.reader.ReaderScreen
import com.abdo.manhwa.ui.reader.ReaderViewModel
import com.abdo.manhwa.ui.splash.SplashScreen

private const val BROWSER_ROUTE = "browser"

/** الرسم البياني الرئيسي للتنقل بين كل شاشات التطبيق، بدعم كامل لاتجاه RTL */
@Composable
fun AbdoManhwaNavHost(
    repository: MangaRepository,
    settingsDataStore: SettingsDataStore
) {
    // مركز التحميل يحتاج النشاط الحالي لاستضافة صفحة الفحص المخفية عند تحميل الفصول
    val hostContext = LocalContext.current
    DisposableEffect(Unit) {
        DownloadCenter.init(hostContext.applicationContext)
        DownloadCenter.setHost(hostContext.findActivity())
        onDispose { DownloadCenter.setHost(null) }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val navController = rememberNavController()

        NavHost(navController = navController, startDestination = Screen.Splash.route) {

            composable(Screen.Splash.route) {
                SplashScreen(onFinished = {
                    navController.navigate(Screen.Library.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                })
            }

            composable(Screen.Library.route) {
                val vm: LibraryViewModel = viewModel(
                    factory = AppViewModelFactory(repository, settingsDataStore) { repo, _ -> LibraryViewModel(repo) }
                )
                LibraryScreen(
                    viewModel = vm,
                    onOpenDetail = { mangaId -> navController.navigate(Screen.Detail.createRoute(mangaId)) },
                    onContinueReading = { mangaId, chapterId ->
                        navController.navigate(Screen.Reader.createRoute(mangaId, chapterId))
                    },
                    onAddManga = { navController.navigate(Screen.AddManga.createRoute()) },
                    onEditManga = { mangaId -> navController.navigate(Screen.AddManga.createRoute(mangaId)) },
                    onOpenBrowser = { navController.navigate(BROWSER_ROUTE) }
                )
            }

            composable(BROWSER_ROUTE) {
                BrowserScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = Screen.AddManga.route,
                arguments = listOf(navArgument("mangaId") { type = NavType.LongType; defaultValue = -1L })
            ) { backStackEntry ->
                val mangaId = backStackEntry.arguments?.getLong("mangaId") ?: -1L
                val vm: AddMangaViewModel = viewModel(
                    factory = AppViewModelFactory(repository, settingsDataStore) { repo, _ -> AddMangaViewModel(repo) }
                )
                AddMangaScreen(
                    viewModel = vm,
                    existingMangaId = if (mangaId > 0) mangaId else null,
                    onDone = {
                        navController.popBackStack(Screen.Library.route, inclusive = false)
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.Detail.route,
                arguments = listOf(navArgument("mangaId") { type = NavType.LongType })
            ) { backStackEntry ->
                val mangaId = backStackEntry.arguments?.getLong("mangaId") ?: -1L
                val vm: DetailViewModel = viewModel(
                    factory = AppViewModelFactory(repository, settingsDataStore) { repo, _ -> DetailViewModel(repo) }
                )
                DetailScreen(
                    viewModel = vm,
                    mangaId = mangaId,
                    onBack = { navController.popBackStack() },
                    onOpenChapter = { chapterId ->
                        navController.navigate(Screen.Reader.createRoute(mangaId, chapterId))
                    },
                    onEdit = { navController.navigate(Screen.AddManga.createRoute(mangaId)) },
                    onAddChapters = { navController.navigate(Screen.AddManga.createRoute(mangaId)) }
                )
            }

            composable(
                route = Screen.Reader.route,
                arguments = listOf(
                    navArgument("mangaId") { type = NavType.LongType },
                    navArgument("chapterId") { type = NavType.LongType }
                )
            ) { backStackEntry ->
                val mangaId = backStackEntry.arguments?.getLong("mangaId") ?: -1L
                val chapterId = backStackEntry.arguments?.getLong("chapterId") ?: -1L
                val vm: ReaderViewModel = viewModel(
                    factory = AppViewModelFactory(repository, settingsDataStore) { repo, settings -> ReaderViewModel(repo, settings) }
                )
                ReaderScreen(
                    viewModel = vm,
                    mangaId = mangaId,
                    chapterId = chapterId,
                    onBack = { navController.popBackStack() },
                    onNavigateToChapter = { newChapterId ->
                        navController.navigate(Screen.Reader.createRoute(mangaId, newChapterId)) {
                            popUpTo(Screen.Reader.route) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
