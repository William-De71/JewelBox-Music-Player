package com.jewelbox.player.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jewelbox.player.ui.albumdetail.AlbumDetailScreen
import com.jewelbox.player.ui.albums.AlbumListScreen
import com.jewelbox.player.ui.player.NowPlayingScreen
import com.jewelbox.player.ui.playlists.PlaylistDetailScreen
import com.jewelbox.player.ui.playlists.PlaylistsScreen
import com.jewelbox.player.ui.playlists.SmartPlaylistScreen
import com.jewelbox.player.ui.settings.SettingsScreen

private object Routes {
    const val ALBUMS = "albums"
    const val SETTINGS = "settings"
    const val ALBUM_DETAIL = "album/{albumId}"
    const val NOW_PLAYING = "player"
    const val PLAYLISTS = "playlists"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    const val SMART_PLAYLIST = "smart/{smartKey}"
    fun albumDetail(id: Int) = "album/$id"
    fun playlistDetail(id: Int) = "playlist/$id"
    fun smartPlaylist(key: String) = "smart/$key"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()

    // Standard bottom-bar pattern: one back-stack entry per tab, whose state is
    // saved and restored when switching back and forth.
    fun switchTab(tab: RootTab) = nav.navigate(
        if (tab == RootTab.LIBRARY) Routes.ALBUMS else Routes.PLAYLISTS,
    ) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    val openPlayer = { nav.navigate(Routes.NOW_PLAYING) }

    // Every screen but the full player carries the same bottom bar; [current]
    // highlights the tab the screen belongs to (null on Settings).
    val bottomBar: @Composable (RootTab?) -> Unit = { current ->
        RootBottomBar(
            current = current,
            onSelectTab = ::switchTab,
            onOpenPlayer = openPlayer,
        )
    }

    NavHost(navController = nav, startDestination = Routes.ALBUMS) {
        composable(Routes.ALBUMS) {
            AlbumListScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenAlbum = { id -> nav.navigate(Routes.albumDetail(id)) },
                bottomBar = { bottomBar(RootTab.LIBRARY) },
            )
        }
        composable(Routes.PLAYLISTS) {
            PlaylistsScreen(
                onOpenPlaylist = { id -> nav.navigate(Routes.playlistDetail(id)) },
                onOpenSmart = { key -> nav.navigate(Routes.smartPlaylist(key)) },
                bottomBar = { bottomBar(RootTab.PLAYLISTS) },
            )
        }
        composable(
            route = Routes.PLAYLIST_DETAIL,
            arguments = listOf(navArgument("playlistId") { type = NavType.IntType }),
        ) { entry ->
            val playlistId = entry.arguments?.getInt("playlistId") ?: return@composable
            PlaylistDetailScreen(
                playlistId = playlistId,
                onBack = { nav.popBackStack() },
                bottomBar = { bottomBar(RootTab.PLAYLISTS) },
            )
        }
        composable(
            route = Routes.SMART_PLAYLIST,
            arguments = listOf(navArgument("smartKey") { type = NavType.StringType }),
        ) { entry ->
            val smartKey = entry.arguments?.getString("smartKey") ?: return@composable
            SmartPlaylistScreen(
                smartKey = smartKey,
                onBack = { nav.popBackStack() },
                bottomBar = { bottomBar(RootTab.PLAYLISTS) },
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
                bottomBar = { bottomBar(RootTab.LIBRARY) },
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
                bottomBar = { bottomBar(null) },
            )
        }
    }
}
