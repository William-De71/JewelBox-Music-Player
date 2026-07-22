package com.jewelbox.player.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jewelbox.player.R
import com.jewelbox.player.data.net.HomeRecentItemDto
import com.jewelbox.player.data.resolveCover
import com.jewelbox.player.playback.PlayerConnection
import com.jewelbox.player.ui.albums.AlbumCard
import com.jewelbox.player.ui.albums.CoverPlayButton
import com.jewelbox.player.ui.playlists.smartSpec

// Twelve columns divide evenly by 2 (recent tiles, 6 columns each) and by 4
// (suggestions, 3 columns each), so both sections share one grid and fit on
// screen together.
private const val COLUMNS = 12

private val RECENT_COVER_SIZE = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenAlbum: (Int) -> Unit,
    onOpenPlaylist: (Int) -> Unit,
    onOpenSmart: (String) -> Unit,
    bottomBar: @Composable () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
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
                is HomeUiState.Loading -> CircularProgressIndicator()

                is HomeUiState.NoServer -> CenteredMessage(
                    text = stringResource(R.string.no_server_configured),
                    actionLabel = stringResource(R.string.open_settings),
                    onAction = onOpenSettings,
                )

                is HomeUiState.ServerTooOld -> CenteredMessage(
                    text = stringResource(R.string.home_server_too_old),
                    actionLabel = stringResource(R.string.retry),
                    onAction = vm::load,
                )

                is HomeUiState.Error -> CenteredMessage(
                    text = stringResource(
                        R.string.error_with_detail,
                        s.message ?: stringResource(R.string.load_failed),
                    ),
                    actionLabel = stringResource(R.string.retry),
                    onAction = vm::load,
                )

                is HomeUiState.Loaded -> HomeContent(
                    state = s,
                    onOpenAlbum = onOpenAlbum,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenSmart = onOpenSmart,
                )
            }
        }
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState.Loaded,
    onOpenAlbum: (Int) -> Unit,
    onOpenPlaylist: (Int) -> Unit,
    onOpenSmart: (String) -> Unit,
) {
    // One flat grid of 6 columns for the whole screen, so both sections fit on
    // screen without scrolling: recent entries span 3 columns each (2 per row,
    // 4 rows) as compact horizontal tiles, suggestions span 2 (3 per row) as
    // small square covers.
    val recent = state.recent
        .filter { it.album != null || it.playlist != null || it.smart != null }
        .take(8)

    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "recent-header", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(stringResource(R.string.home_recent_header))
        }
        if (recent.isEmpty()) {
            item(key = "recent-empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.home_empty_recent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(
                items = recent,
                key = { "recent-${it.itemType}-${it.album?.id ?: it.playlist?.id ?: it.smart?.key}" },
                span = { GridItemSpan(COLUMNS / 2) },
            ) { entry ->
                RecentTile(
                    entry = entry,
                    serverUrl = state.serverUrl,
                    onOpenAlbum = onOpenAlbum,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenSmart = onOpenSmart,
                )
            }
        }

        if (state.latest.isNotEmpty()) {
            item(key = "latest-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(stringResource(R.string.home_latest_header))
            }
            items(
                items = state.latest,
                key = { "latest-${it.id}" },
                span = { GridItemSpan(COLUMNS / 4) },
            ) { album ->
                AlbumCard(
                    album = album,
                    serverUrl = state.serverUrl,
                    onClick = { onOpenAlbum(album.id) },
                    onPlay = { PlayerConnection.playAlbumById(state.serverUrl, album.id) },
                )
            }
        }

        if (state.suggestions.isNotEmpty()) {
            item(key = "suggestions-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(stringResource(R.string.home_suggestions_header))
            }
            items(
                items = state.suggestions,
                key = { "suggestion-${it.id}" },
                span = { GridItemSpan(COLUMNS / 4) },
            ) { album ->
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
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Compact horizontal tile for the recent section: a small cover with the title
 * and subtitle beside it. Much shorter than a square card, so the 8 entries and
 * the suggestions below them fit on one screen.
 */
@Composable
private fun RecentTile(
    entry: HomeRecentItemDto,
    serverUrl: String,
    onOpenAlbum: (Int) -> Unit,
    onOpenPlaylist: (Int) -> Unit,
    onOpenSmart: (String) -> Unit,
) {
    val album = entry.album
    val playlist = entry.playlist
    val smart = entry.smart
    // Smart playlists have no cover of their own: their label and icon are
    // resolved client-side from the key, like on the Playlists screen.
    val spec = smart?.let { smartSpec(it.key) }

    val onClick = when {
        album != null -> ({ onOpenAlbum(album.id) })
        playlist != null -> ({ onOpenPlaylist(playlist.id) })
        smart != null -> ({ onOpenSmart(smart.key) })
        else -> return
    }

    val title = when {
        album != null -> album.title
        playlist != null -> playlist.name
        spec != null -> stringResource(spec.label)
        else -> smart?.key.orEmpty()
    }
    val subtitle = when {
        album != null -> album.artist.name
        smart != null -> stringResource(R.string.track_count, smart.trackCount)
        else -> stringResource(R.string.track_count, playlist?.trackCount ?: 0)
    }
    // Play overlay: albums with audio, any playlist, and non-empty smart lists.
    val onPlay: (() -> Unit)? = when {
        album != null && album.hasAudio -> ({ PlayerConnection.playAlbumById(serverUrl, album.id) })
        playlist != null && playlist.trackCount > 0 -> ({ PlayerConnection.playPlaylistById(serverUrl, playlist.id) })
        smart != null && smart.trackCount > 0 -> ({ PlayerConnection.playSmartByKey(serverUrl, smart.key) })
        else -> null
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(RECENT_COVER_SIZE)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val cover = if (smart != null) null else resolveCover(serverUrl, album?.coverUrl ?: playlist?.coverUrl)
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = when {
                        album != null -> Icons.Filled.Album
                        spec != null -> spec.icon
                        else -> Icons.AutoMirrored.Filled.QueueMusic
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxSize(0.5f),
                )
            }

            if (onPlay != null) {
                CoverPlayButton(
                    onClick = onPlay,
                    size = 24,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
