package com.jewelbox.player.ui.albums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jewelbox.player.R
import com.jewelbox.player.playback.PlayerConnection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumListScreen(
    onOpenSettings: () -> Unit,
    onOpenAlbum: (Int) -> Unit,
    bottomBar: @Composable () -> Unit,
    vm: AlbumListViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    val loaded = state as? AlbumsUiState.Loaded
                    val grouped = loaded?.groupByArtist ?: false
                    // "Collapse all" only if at least one group is currently open.
                    val anyExpanded = grouped && loaded != null &&
                        loaded.albums.any { it.artist.name !in loaded.collapsedArtists }
                    OverflowMenu(
                        groupByArtist = grouped,
                        anyExpanded = anyExpanded,
                        onToggleGroup = { vm.setGroupByArtist(!grouped) },
                        onCollapseAll = vm::collapseAll,
                        onExpandAll = vm::expandAll,
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is AlbumsUiState.Loading -> CircularProgressIndicator()

                is AlbumsUiState.NoServer -> CenteredMessage(
                    text = stringResource(R.string.no_server_configured),
                    actionLabel = stringResource(R.string.open_settings),
                    onAction = onOpenSettings,
                )

                is AlbumsUiState.Error -> CenteredMessage(
                    text = stringResource(
                        R.string.error_with_detail,
                        s.message ?: stringResource(R.string.load_failed),
                    ),
                    actionLabel = stringResource(R.string.retry),
                    onAction = vm::load,
                )

                is AlbumsUiState.Loaded -> {
                    if (s.albums.isEmpty()) {
                        CenteredMessage(
                            text = stringResource(R.string.empty_collection),
                            actionLabel = stringResource(R.string.refresh),
                            onAction = vm::load,
                        )
                    } else {
                        AlbumGrid(
                            state = s,
                            onOpenAlbum = onOpenAlbum,
                            onToggleArtist = vm::toggleArtist,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    groupByArtist: Boolean,
    anyExpanded: Boolean,
    onToggleGroup: () -> Unit,
    onCollapseAll: () -> Unit,
    onExpandAll: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.group_by_artist)) },
            onClick = {
                onToggleGroup()
                expanded = false
            },
            leadingIcon = {
                if (groupByArtist) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                }
            },
        )
        if (groupByArtist) {
            if (anyExpanded) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.collapse_all)) },
                    onClick = {
                        onCollapseAll()
                        expanded = false
                    },
                    leadingIcon = { Icon(Icons.Filled.UnfoldLess, contentDescription = null) },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.expand_all)) },
                    onClick = {
                        onExpandAll()
                        expanded = false
                    },
                    leadingIcon = { Icon(Icons.Filled.UnfoldMore, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    state: AlbumsUiState.Loaded,
    onOpenAlbum: (Int) -> Unit,
    onToggleArtist: (String) -> Unit,
) {
    LazyVerticalGrid(
        // Four per row, matching the home screen's suggestion grid.
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // Tighter vertical rhythm when grouped so a collapsed list of headers stays compact.
        verticalArrangement = Arrangement.spacedBy(if (state.groupByArtist) 4.dp else 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.groupByArtist) {
            // Albums arrive already sorted by artist then year, so a simple
            // group-preserving pass keeps that order within each artist.
            val groups = state.albums.groupBy { it.artist.name }
            groups.forEach { (artist, albums) ->
                val collapsed = artist in state.collapsedArtists
                item(key = "header-$artist", span = { GridItemSpan(maxLineSpan) }) {
                    ArtistHeader(
                        name = artist,
                        count = albums.size,
                        collapsed = collapsed,
                        onClick = { onToggleArtist(artist) },
                    )
                }
                if (!collapsed) {
                    items(items = albums, key = { it.id }) { album ->
                        AlbumCard(
                            album = album,
                            serverUrl = state.serverUrl,
                            onClick = { onOpenAlbum(album.id) },
                            onPlay = { PlayerConnection.playAlbumById(state.serverUrl, album.id) },
                        )
                    }
                }
            }
        } else {
            items(items = state.albums, key = { it.id }) { album ->
                AlbumCard(
                    album = album,
                    serverUrl = state.serverUrl,
                    onClick = { onOpenAlbum(album.id) },
                    onPlay = { PlayerConnection.playAlbumById(state.serverUrl, album.id) },
                )
            }
        }
    }
}

@Composable
private fun ArtistHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            contentDescription = if (collapsed) stringResource(R.string.expand) else stringResource(R.string.collapse),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f, fill = false),
        )
        Text(
            text = "  ($count)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAction) { Text(actionLabel) }
    }
}
