package com.jewelbox.player.ui.offline

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.jewelbox.player.R
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.offline.ItemStatus
import com.jewelbox.player.data.offline.albumKeys
import com.jewelbox.player.data.offline.itemStatus
import com.jewelbox.player.data.offline.playlistKeys
import com.jewelbox.player.data.resolveCover
import com.jewelbox.player.playback.Offline
import com.jewelbox.player.playback.PlayerConnection
import com.jewelbox.player.playback.QueueSource
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What is on the phone for offline listening: albums and playlists, their
 * download state, the space used, and the plays waiting for the server.
 * Works without the server: everything comes from the offline catalog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit, bottomBar: @Composable () -> Unit) {
    val catalog by ServiceLocator.offlineStore.catalog.collectAsStateWithLifecycle()
    val progress by Offline.progress.collectAsStateWithLifecycle()
    val bytes by Offline.bytes.collectAsStateWithLifecycle()
    val pending by ServiceLocator.offlineStore.pendingPlays.collectAsStateWithLifecycle()
    val onMobile by remember { ServiceLocator.serverPrefs.downloadOnMobile.map { it } }
        .collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var removing by remember { mutableStateOf<(() -> Unit)?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.offline_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.offline_space, Formatter.formatShortFileSize(context, bytes)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (pending.isNotEmpty()) {
                        Text(
                            pluralStringResource(R.plurals.offline_pending_plays, pending.size, pending.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { scope.launch { ServiceLocator.serverPrefs.setDownloadOnMobile(!onMobile) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.offline_on_mobile),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Switch(
                            checked = onMobile,
                            onCheckedChange = { scope.launch { ServiceLocator.serverPrefs.setDownloadOnMobile(it) } },
                        )
                    }
                }
                HorizontalDivider()
            }

            if (catalog.albums.isEmpty() && catalog.playlists.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.offline_empty),
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            if (catalog.albums.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.offline_albums)) }
                items(catalog.albums.sortedBy { it.album.artist.name.lowercase() }, key = { "a:${it.scope}:${it.album.id}" }) { item ->
                    val status = itemStatus(true, albumKeys(item.scope, item.album), progress)
                    OfflineRow(
                        title = item.album.title,
                        subtitle = item.album.artist.name,
                        cover = Offline.localCover(item.album.coverUrl) ?: resolveCover(item.serverUrl, item.album.coverUrl),
                        isPlaylist = false,
                        status = status,
                        onPlay = {
                            val first = item.album.tracks.firstOrNull { it.hasFile } ?: return@OfflineRow
                            PlayerConnection.playAlbum(item.serverUrl, item.album, first.id, offlineScope = item.scope)
                        },
                        onRemove = { removing = { scope.launch { Offline.removeAlbum(item.scope, item.album.id) } } },
                    )
                }
            }

            if (catalog.playlists.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.offline_playlists)) }
                items(catalog.playlists.sortedBy { it.playlist.name.lowercase() }, key = { "p:${it.scope}:${it.playlist.id}" }) { item ->
                    val status = itemStatus(true, playlistKeys(item.scope, item.playlist), progress)
                    val count = item.playlist.tracks.count { it.hasFile }
                    val firstCover = item.playlist.tracks.firstNotNullOfOrNull { it.coverUrl }
                    OfflineRow(
                        title = item.playlist.name,
                        subtitle = pluralStringResource(R.plurals.offline_tracks, count, count),
                        cover = Offline.localCover(firstCover) ?: resolveCover(item.serverUrl, firstCover),
                        isPlaylist = true,
                        status = status,
                        onPlay = {
                            PlayerConnection.playQueue(
                                item.serverUrl,
                                item.playlist.tracks,
                                source = QueueSource.Playlist(item.playlist.id),
                                sourceLabel = item.playlist.name,
                                offlineScope = item.scope,
                            )
                        },
                        onRemove = { removing = { scope.launch { Offline.removePlaylist(item.scope, item.playlist.id) } } },
                    )
                }
            }
        }
    }

    removing?.let { action ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.offline_remove_title)) },
            text = { Text(stringResource(R.string.offline_remove_text)) },
            confirmButton = {
                TextButton(onClick = { removing = null; action() }) { Text(stringResource(R.string.offline_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun OfflineRow(
    title: String,
    subtitle: String,
    cover: String?,
    isPlaylist: Boolean,
    status: ItemStatus,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(if (isPlaylist) Icons.AutoMirrored.Filled.QueueMusic else Icons.Filled.Album, contentDescription = null)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (status) {
                is ItemStatus.Downloading -> {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { status.fraction }, modifier = Modifier.fillMaxWidth())
                    if (status.waiting) {
                        Text(
                            stringResource(R.string.offline_waiting),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is ItemStatus.Failed -> Text(
                    pluralStringResource(R.plurals.offline_failed, status.failed, status.failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.offline_remove))
        }
    }
}
