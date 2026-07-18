package com.jewelbox.player.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jewelbox.player.R
import com.jewelbox.player.data.formatDurationSeconds
import com.jewelbox.player.data.formatUpdatedAt
import com.jewelbox.player.data.net.PlaylistSummaryDto
import com.jewelbox.player.playback.PlayerConnection
import com.jewelbox.player.playback.QueueSource

/** Target of the name dialog: creating, or renaming an existing playlist. */
private sealed interface NameDialogTarget {
    data object Create : NameDialogTarget
    data class Rename(val id: Int, val currentName: String) : NameDialogTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onOpenPlaylist: (Int) -> Unit,
    onOpenSmart: (String) -> Unit,
    bottomBar: @Composable () -> Unit,
    vm: PlaylistsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var nameDialog by remember { mutableStateOf<NameDialogTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistSummaryDto?>(null) }

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
                title = { Text(stringResource(R.string.playlists_title)) },
                actions = {
                    IconButton(onClick = { nameDialog = NameDialogTarget.Create }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.playlist_create))
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
                is PlaylistsUiState.Loading -> CircularProgressIndicator()

                is PlaylistsUiState.Error -> Column(
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
                    Button(onClick = { vm.load() }) { Text(stringResource(R.string.retry)) }
                }

                is PlaylistsUiState.Loaded -> PlaylistsContent(
                    state = s,
                    onOpenSmart = onOpenSmart,
                    onOpenPlaylist = onOpenPlaylist,
                    onCreate = { nameDialog = NameDialogTarget.Create },
                    onRename = { nameDialog = NameDialogTarget.Rename(it.id, it.name) },
                    onDelete = { deleteTarget = it },
                )
            }
        }
    }

    when (val dialog = nameDialog) {
        null -> Unit
        is NameDialogTarget.Create -> PlaylistNameDialog(
            title = stringResource(R.string.playlist_create_title),
            confirmLabel = stringResource(R.string.save),
            onConfirm = { name ->
                nameDialog = null
                vm.create(name, onCreated = onOpenPlaylist)
            },
            onDismiss = { nameDialog = null },
        )
        is NameDialogTarget.Rename -> PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename_title),
            confirmLabel = stringResource(R.string.save),
            initialName = dialog.currentName,
            onConfirm = { name ->
                nameDialog = null
                vm.rename(dialog.id, name)
            },
            onDismiss = { nameDialog = null },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            playlistName = target.name,
            onConfirm = {
                deleteTarget = null
                vm.delete(target.id)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun PlaylistsContent(
    state: PlaylistsUiState.Loaded,
    onOpenSmart: (String) -> Unit,
    onOpenPlaylist: (Int) -> Unit,
    onCreate: () -> Unit,
    onRename: (PlaylistSummaryDto) -> Unit,
    onDelete: (PlaylistSummaryDto) -> Unit,
) {
    // Flag the row whose queue is loaded in the player.
    val queueSource by PlayerConnection.queueSource.collectAsStateWithLifecycle()
    val playback by PlayerConnection.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item { SectionHeader(stringResource(R.string.smart_playlists_header)) }
        items(SMART_SPECS.filter { spec -> state.smart.any { it.key == spec.key } }) { spec ->
            val count = state.smart.first { it.key == spec.key }.trackCount
            val isPlaying = playback.hasItem &&
                (queueSource as? QueueSource.Smart)?.key == spec.key
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSmart(spec.key) }
                    .padding(vertical = 12.dp),
            ) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = null,
                    tint = if (isPlaying) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(spec.label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f),
                )
                if (isPlaying) NowPlayingBadge()
                Text(
                    text = stringResource(R.string.track_count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }

        item { SectionHeader(stringResource(R.string.my_playlists_header), topPadding = 24.dp) }
        if (state.playlists.isEmpty()) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                ) {
                    Text(
                        stringResource(R.string.playlist_empty_list),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.playlist_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onCreate) { Text(stringResource(R.string.playlist_create)) }
                }
            }
        } else {
            items(items = state.playlists, key = { it.id }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    isPlaying = playback.hasItem &&
                        (queueSource as? QueueSource.Playlist)?.playlistId == playlist.id,
                    onOpen = { onOpenPlaylist(playlist.id) },
                    onRename = { onRename(playlist) },
                    onDelete = { onDelete(playlist) },
                )
                HorizontalDivider()
            }
        }
    }
}

/** Small equalizer glyph marking the playlist currently loaded in the player. */
@Composable
private fun NowPlayingBadge() {
    Icon(
        imageVector = Icons.Filled.GraphicEq,
        contentDescription = stringResource(R.string.now_playing_indicator),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
private fun SectionHeader(text: String, topPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = topPadding, bottom = 8.dp),
    )
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistSummaryDto,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = null,
            tint = if (isPlaying) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                add(stringResource(R.string.track_count, playlist.trackCount))
                if (playlist.totalDurationSeconds > 0) add(formatDurationSeconds(playlist.totalDurationSeconds))
                playlist.updatedAt?.let { add(stringResource(R.string.playlist_updated_at, formatUpdatedAt(it))) }
            }
            Text(
                text = meta.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isPlaying) NowPlayingBadge()
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_rename)) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_delete)) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}
