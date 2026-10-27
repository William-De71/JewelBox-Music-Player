package com.jewelbox.player.ui.offline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jewelbox.player.R
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.offline.ItemStatus
import com.jewelbox.player.playback.Offline

/**
 * Recomposes on every change that can move the download state of an item:
 * its progress, the catalog, the collection served. [status] reads them.
 */
@Composable
fun rememberOfflineStatus(status: () -> ItemStatus): ItemStatus {
    val progress by Offline.progress.collectAsStateWithLifecycle()
    val catalog by ServiceLocator.offlineStore.catalog.collectAsStateWithLifecycle()
    val scope by Offline.currentScope.collectAsStateWithLifecycle()
    return remember(progress, catalog, scope) { status() }
}

/**
 * Top-bar button of an album or a playlist: download it for offline
 * listening, see the progress, retry a failure, or remove it (confirmed).
 */
@Composable
fun OfflineAction(status: ItemStatus, onDownload: () -> Unit, onRemove: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    when (status) {
        ItemStatus.NotDownloaded -> IconButton(onClick = onDownload) {
            Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.offline_download))
        }
        is ItemStatus.Downloading -> IconButton(onClick = { confirm = true }) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { status.fraction.coerceAtLeast(0.02f) },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                if (status.waiting) {
                    Icon(
                        Icons.Outlined.HourglassEmpty,
                        contentDescription = stringResource(R.string.offline_waiting),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        ItemStatus.Downloaded -> IconButton(onClick = { confirm = true }) {
            Icon(
                Icons.Filled.DownloadDone,
                contentDescription = stringResource(R.string.offline_downloaded),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        is ItemStatus.Failed -> IconButton(onClick = onDownload) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = stringResource(R.string.offline_retry),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.offline_remove_title)) },
            text = { Text(stringResource(R.string.offline_remove_text)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onRemove() }) {
                    Text(stringResource(R.string.offline_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
