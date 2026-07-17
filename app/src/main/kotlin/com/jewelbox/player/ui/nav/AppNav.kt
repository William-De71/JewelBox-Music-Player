package com.jewelbox.player.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jewelbox.player.ui.albumdetail.AlbumDetailScreen
import com.jewelbox.player.ui.albums.AlbumListScreen
import com.jewelbox.player.ui.player.NowPlayingScreen
import com.jewelbox.player.ui.settings.SettingsScreen

private object Routes {
    const val ALBUMS = "albums"
    const val SETTINGS = "settings"
    const val ALBUM_DETAIL = "album/{albumId}"
    const val NOW_PLAYING = "player"
    fun albumDetail(id: Int) = "album/$id"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.ALBUMS) {
        composable(Routes.ALBUMS) {
            AlbumListScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenAlbum = { id -> nav.navigate(Routes.albumDetail(id)) },
                onOpenPlayer = { nav.navigate(Routes.NOW_PLAYING) },
            )
        }
        composable(
            route = Routes.ALBUM_DETAIL,
            arguments = listOf(navArgument("albumId") { type = NavType.IntType }),
        ) { entry ->
            val albumId = entry.arguments?.getInt("albumId") ?: return@composable
            AlbumDetailScreen(
                albumId = albumId,
                onBack = { nav.popBackStack() },
                onOpenPlayer = { nav.navigate(Routes.NOW_PLAYING) },
            )
        }
        composable(Routes.NOW_PLAYING) {
            NowPlayingScreen(
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
            )
        }
    }
}
