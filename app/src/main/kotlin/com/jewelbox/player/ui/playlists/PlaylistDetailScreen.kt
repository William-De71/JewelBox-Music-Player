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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jewelbox.player.R
import com.jewelbox.player.data.net.PlaylistDto
import com.jewelbox.player.playback.PlayerConnection
import com.jewelbox.player.playback.QueueSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Int,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
    vm: PlaylistDetailViewModel = viewModel(factory = PlaylistDetailViewModel.Factory(playlistId)),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    notice?.let { n ->
        val text = noticeText(n)
        LaunchedEffect(n) {
            snackbarHostState.showSnackbar(text)
            vm.consumeNotice()
        }
    }

    val loaded = state as? PlaylistDetailUiState.Loaded

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        loaded?.playlist?.name ?: stringResource(R.string.playlists_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (loaded != null) {
                        IconButton(onClick = { renameOpen = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.playlist_rename))
                        }
                        IconButton(onClick = { deleteOpen = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.playlist_delete))
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
                is PlaylistDetailUiState.Loading -> CircularProgressIndicator()

                is PlaylistDetailUiState.Error -> Column(
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

                is PlaylistDetailUiState.Loaded -> PlaylistDetailContent(
                    serverUrl = s.serverUrl,
                    playlist = s.playlist,
                    vm = vm,
                )
            }
        }
    }

    if (renameOpen && loaded != null) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename_title),
            confirmLabel = stringResource(R.string.save),
            initialName = loaded.playlist.name,
            onConfirm = { name ->
                renameOpen = false
                vm.rename(name)
            },
            onDismiss = { renameOpen = false },
        )
    }
    if (deleteOpen && loaded != null) {
        ConfirmDeleteDialog(
            playlistName = loaded.playlist.name,
            onConfirm = {
                deleteOpen = false
                vm.delete(onDeleted = onBack)
            },
            onDismiss = { deleteOpen = false },
        )
    }
}

@Composable
private fun PlaylistDetailContent(
    serverUrl: String,
    playlist: PlaylistDto,
    vm: PlaylistDetailViewModel,
) {
    val playback by PlayerConnection.state.collectAsStateWithLifecycle()
    val playable = playlist.tracks.filter { it.hasFile }

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
                    text = stringResource(R.string.track_count, playlist.tracks.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (playable.isNotEmpty()) {
                    Button(onClick = {
                        PlayerConnection.playQueue(
                            serverUrl, playlist.tracks,
                            source = QueueSource.Playlist(playlist.id),
                        )
                    }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 2.dp))
                        Text(stringResource(R.string.playlist_listen))
                    }
                }
            }
        }

        if (playlist.tracks.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.playlist_no_playable_tracks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        } else {
            itemsIndexed(
                items = playlist.tracks,
                key = { _, track -> track.entryId ?: track.id },
            ) { index, track ->
                val isCurrent = playback.currentTrackId == track.id
                QueueTrackRow(
                    track = track,
                    position = index + 1,
                    isCurrent = isCurrent,
                    onPlay = {
                        if (isCurrent) {
                            PlayerConnection.togglePlayPause()
                        } else {
                            val start = playable.indexOfFirst { it.entryId == track.entryId }
                            PlayerConnection.playQueue(
                                serverUrl, playlist.tracks, start,
                                source = QueueSource.Playlist(playlist.id),
                            )
                        }
                    },
                    onToggleFavorite = { vm.toggleFavorite(track.id) },
                    trailing = {
                        EntryMenu(
                            canMoveUp = index > 0,
                            canMoveDown = index < playlist.tracks.lastIndex,
                            onMoveUp = { vm.move(index, -1) },
                            onMoveDown = { vm.move(index, +1) },
                            onRemove = { track.entryId?.let(vm::removeEntry) },
                        )
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun EntryMenu(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_up)) },
                enabled = canMoveUp,
                onClick = {
                    open = false
                    onMoveUp()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_down)) },
                enabled = canMoveDown,
                onClick = {
                    open = false
                    onMoveDown()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remove_from_playlist)) },
                onClick = {
                    open = false
                    onRemove()
                },
            )
        }
    }
}
