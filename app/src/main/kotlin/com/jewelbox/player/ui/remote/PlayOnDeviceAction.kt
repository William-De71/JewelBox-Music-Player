package com.jewelbox.player.ui.remote

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jewelbox.player.R
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.RemoteDeviceDto
import com.jewelbox.player.playback.RemoteCommands
import com.jewelbox.player.playback.RemoteMedia
import com.jewelbox.player.playback.controllableOthers
import kotlinx.coroutines.launch

/**
 * Top bar action "Play on…": [media] started on another player instead of
 * this phone. The players are listed when the menu opens: those that can be
 * driven and start a media.
 */
@Composable
fun PlayOnDeviceAction(media: RemoteMedia) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = ServiceLocator.remoteRepository
    var expanded by remember { mutableStateOf(false) }
    // Null while the list is on its way.
    var devices by remember { mutableStateOf<List<RemoteDeviceDto>?>(null) }

    Box {
        IconButton(onClick = {
            expanded = true
            devices = null
            scope.launch {
                devices = runCatching {
                    controllableOthers(repo.devices(), repo.ownDeviceId()).filter { "play_media" in it.capabilities }
                }.getOrDefault(emptyList())
            }
        }) {
            Icon(Icons.Filled.Cast, contentDescription = stringResource(R.string.remote_play_on))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.remote_play_on)) }, onClick = {}, enabled = false)
            val list = devices
            when {
                list == null -> DropdownMenuItem(
                    text = { Text(stringResource(R.string.remote_play_on_searching)) },
                    onClick = {},
                    enabled = false,
                )
                list.isEmpty() -> DropdownMenuItem(
                    text = { Text(stringResource(R.string.remote_play_on_none)) },
                    onClick = {},
                    enabled = false,
                )
                else -> list.forEach { device ->
                    val name = deviceName(device)
                    DropdownMenuItem(
                        text = { Text(name) },
                        leadingIcon = { Icon(deviceIcon(device.deviceLabel), contentDescription = null) },
                        onClick = {
                            expanded = false
                            scope.launch {
                                val sent = runCatching { repo.send(device.deviceId, RemoteCommands.playMedia(media)) }.isSuccess
                                val message = if (sent) {
                                    context.getString(R.string.remote_play_on_started, name)
                                } else {
                                    context.getString(R.string.remote_command_failed)
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }
    }
}
