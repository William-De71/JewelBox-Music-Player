package com.jewelbox.player.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jewelbox.player.R
import com.jewelbox.player.ui.player.MiniPlayer

/** The three root destinations reachable from the bottom navigation bar. */
enum class RootTab { LIBRARY, SEARCH, PLAYLISTS }

/**
 * Bottom area shared by every screen except the full player: the mini-player
 * sitting right on top of the tab bar. [current] is null on screens that don't
 * belong to a tab (Settings), leaving both tabs unselected.
 */
@Composable
fun RootBottomBar(
    current: RootTab?,
    onSelectTab: (RootTab) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    Column {
        MiniPlayer(onOpen = onOpenPlayer, padBottomInset = false)
        NavigationBar {
            NavigationBarItem(
                selected = current == RootTab.LIBRARY,
                onClick = { onSelectTab(RootTab.LIBRARY) },
                icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
                label = { Text(stringResource(R.string.library_title)) },
            )
            NavigationBarItem(
                selected = current == RootTab.SEARCH,
                onClick = { onSelectTab(RootTab.SEARCH) },
                icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                label = { Text(stringResource(R.string.search_title)) },
            )
            NavigationBarItem(
                selected = current == RootTab.PLAYLISTS,
                onClick = { onSelectTab(RootTab.PLAYLISTS) },
                icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                label = { Text(stringResource(R.string.playlists_title)) },
            )
        }
    }
}
