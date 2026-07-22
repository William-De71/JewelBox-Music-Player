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
import com.jewelbox.player.ui.home.HomeScreen
import com.jewelbox.player.ui.player.NowPlayingScreen
import com.jewelbox.player.ui.playlists.PlaylistDetailScreen
import com.jewelbox.player.ui.playlists.PlaylistsScreen
import com.jewelbox.player.ui.playlists.SmartPlaylistScreen
import com.jewelbox.player.ui.search.SearchScreen
import com.jewelbox.player.ui.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val ALBUMS = "albums"
    const val SEARCH = "search"
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

    // Pressing a tab always lands on that tab's root screen, never on a detail
    // it was left on: everything above the start destination is popped, and the
    // saved stacks are deliberately not restored (restoreState would resurface
    // an open album or playlist). Detail screens stay reachable via Back.
    fun switchTab(tab: RootTab) {
        val route = when (tab) {
            RootTab.HOME -> Routes.HOME
            RootTab.LIBRARY -> Routes.ALBUMS
            RootTab.SEARCH -> Routes.SEARCH
            RootTab.PLAYLISTS -> Routes.PLAYLISTS
        }
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
            launchSingleTop = true
        }
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

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenAlbum = { id -> nav.navigate(Routes.albumDetail(id)) },
                onOpenPlaylist = { id -> nav.navigate(Routes.playlistDetail(id)) },
                onOpenSmart = { key -> nav.navigate(Routes.smartPlaylist(key)) },
                bottomBar = { bottomBar(RootTab.HOME) },
            )
        }
        composable(Routes.ALBUMS) {
            AlbumListScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenAlbum = { id -> nav.navigate(Routes.albumDetail(id)) },
                bottomBar = { bottomBar(RootTab.LIBRARY) },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onOpenAlbum = { id -> nav.navigate(Routes.albumDetail(id)) },
                bottomBar = { bottomBar(RootTab.SEARCH) },
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
