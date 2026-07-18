package com.jewelbox.player.ui.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jewelbox.player.R
import com.jewelbox.player.data.net.QueueTrackDto
import com.jewelbox.player.playback.PlayerConnection
import com.jewelbox.player.playback.QueueSource

/** Read-only detail of a smart playlist; "dynamic_mix" plays with rotation on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPlaylistScreen(
    smartKey: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
    vm: SmartPlaylistViewModel = viewModel(factory = SmartPlaylistViewModel.Factory(smartKey)),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val spec = smartSpec(smartKey)

    notice?.let { n ->
        val text = noticeText(n)
        LaunchedEffect(n) {
            snackbarHostState.showSnackbar(text)
            vm.consumeNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        spec?.let {
                            Icon(it.icon, contentDescription = null)
                            Spacer(Modifier.padding(horizontal = 4.dp))
                        }
                        Text(spec?.let { stringResource(it.label) } ?: smartKey)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // The dynamic mix can be thrown away and redrawn from scratch.
                    if (smartKey == DYNAMIC_MIX_KEY) {
                        IconButton(onClick = vm::refreshMix) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.new_mix))
                        }
                    }
                },
            )
        },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is SmartPlaylistUiState.Loading -> CircularProgressIndicator()

                is SmartPlaylistUiState.Error -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.error_with_detail,
                            s.message ?: stringResource(R.string.load_failed),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = vm::load) { Text(stringResource(R.string.retry)) }
                }

                is SmartPlaylistUiState.Loaded -> SmartPlaylistContent(
                    serverUrl = s.serverUrl,
                    smartKey = smartKey,
                    tracks = s.tracks,
                    onToggleFavorite = vm::toggleFavorite,
                    onRemoveTrack = if (smartKey == DYNAMIC_MIX_KEY) vm::removeMixTrack else null,
                )
            }
        }
    }
}

@Composable
private fun SmartPlaylistContent(
    serverUrl: String,
    smartKey: String,
    tracks: List<QueueTrackDto>,
    onToggleFavorite: (Int) -> Unit,
    onRemoveTrack: ((Int) -> Unit)? = null,
) {
    val playback by PlayerConnection.state.collectAsStateWithLifecycle()
    val dynamic = smartKey == DYNAMIC_MIX_KEY
    val playable = tracks.filter { it.hasFile }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.track_count, tracks.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (playable.isNotEmpty()) {
                    Button(
                        onClick = {
                            PlayerConnection.playQueue(
                                serverUrl, tracks,
                                dynamicMix = dynamic,
                                source = QueueSource.Smart(smartKey),
                            )
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 2.dp))
                        Text(stringResource(R.string.playlist_listen))
                    }
                }
            }
        }

        if (tracks.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.playlist_no_playable_tracks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        } else {
            itemsIndexed(items = tracks, key = { i, track -> "${track.id}-$i" }) { index, track ->
                val isCurrent = playback.currentTrackId == track.id
                QueueTrackRow(
                    track = track,
                    position = index + 1,
                    isCurrent = isCurrent,
                    onPlay = {
                        if (isCurrent) {
                            PlayerConnection.togglePlayPause()
                        } else {
                            val start = playable.indexOfFirst { it.id == track.id }
                            PlayerConnection.playQueue(
                                serverUrl, tracks, start,
                                dynamicMix = dynamic,
                                source = QueueSource.Smart(smartKey),
                            )
                        }
                    },
                    onToggleFavorite = { onToggleFavorite(track.id) },
                    trailing = onRemoveTrack?.let { remove ->
                        {
                            IconButton(onClick = { remove(track.id) }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.mix_remove_track),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
