package com.abdo.manhwa.ui.navigation

/** يعرّف كل الوجهات (الشاشات) في التطبيق ومساراتها */
sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Library : Screen("library")
    object AddManga : Screen("add_manga?mangaId={mangaId}") {
        fun createRoute(mangaId: Long? = null) = "add_manga?mangaId=${mangaId ?: -1}"
    }
    object Detail : Screen("detail/{mangaId}") {
        fun createRoute(mangaId: Long) = "detail/$mangaId"
    }
    object Reader : Screen("reader/{mangaId}/{chapterId}") {
        fun createRoute(mangaId: Long, chapterId: Long) = "reader/$mangaId/$chapterId"
    }
}
