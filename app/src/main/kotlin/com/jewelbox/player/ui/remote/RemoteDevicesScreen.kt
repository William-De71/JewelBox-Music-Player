package com.jewelbox.player.ui.remote

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jewelbox.player.R
import com.jewelbox.player.data.net.RemoteDeviceDto
import com.jewelbox.player.data.resolveCover
import com.jewelbox.player.playback.RemoteCommands

/** The other players this phone can drive; tapping one opens its remote. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteDevicesScreen(
    onBack: () -> Unit,
    onOpenDevice: (String) -> Unit,
    onListenedHere: () -> Unit,
    vm: RemoteViewModel = viewModel(),
) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    CommandFailedToast(vm)
    ListenedHereEffect(vm, onListenedHere)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_devices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        val list = devices
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text(
                    stringResource(R.string.remote_devices_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.deviceId }) { device ->
                        DeviceCard(
                            device = device,
                            serverUrl = serverUrl,
                            onOpen = { onOpenDevice(device.deviceId) },
                            onTogglePlay = { vm.send(RemoteCommands.simple("toggle"), device.deviceId) },
                            onListenHere = { vm.listenHere(device.deviceId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: RemoteDeviceDto,
    serverUrl: String,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onListenHere: () -> Unit,
) {
    val state = device.state
    val track = state?.track
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            val cover = resolveCover(serverUrl, track?.coverUrl)
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(deviceIcon(device.deviceLabel), contentDescription = null, modifier = Modifier.size(56.dp))
            }
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        deviceIcon(device.deviceLabel),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        deviceName(device),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    track?.let { if (it.artistName.isBlank()) it.title else "${it.title} — ${it.artistName}" }
                        ?: stringResource(R.string.remote_nothing_playing),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        when (state?.playbackState) {
                            "playing" -> R.string.remote_state_playing
                            "paused" -> R.string.remote_state_paused
                            else -> R.string.remote_state_idle
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (track != null) {
                val playing = state.playbackState == "playing"
                IconButton(onClick = onListenHere) {
                    Icon(Icons.Filled.InstallMobile, contentDescription = stringResource(R.string.remote_listen_here))
                }
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                    )
                }
            }
        }
    }
}

/** The label the player gave itself ("JewelBox bureau · host", "JewelBox Android · model", a browser…). */
@Composable
fun deviceName(device: RemoteDeviceDto): String =
    device.deviceLabel?.takeIf { it.isNotBlank() } ?: stringResource(R.string.remote_unnamed_device)

fun deviceIcon(label: String?): ImageVector {
    val text = label.orEmpty().lowercase()
    return when {
        "android" in text -> Icons.Filled.PhoneAndroid
        "bureau" in text || "desktop" in text || "windows" in text -> Icons.Filled.Computer
        else -> Icons.Filled.Language
    }
}

@Composable
internal fun CommandFailedToast(vm: RemoteViewModel) {
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.commandFailed.collect {
            Toast.makeText(context, R.string.remote_command_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

/** After "Listen here": on to this phone's player, or a toast when it could not. */
@Composable
internal fun ListenedHereEffect(vm: RemoteViewModel, onListenedHere: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.listenedHere.collect { started ->
            if (started) {
                onListenedHere()
            } else {
                Toast.makeText(context, R.string.remote_listen_here_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
