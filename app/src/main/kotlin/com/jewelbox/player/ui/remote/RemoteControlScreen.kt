package com.jewelbox.player.ui.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jewelbox.player.R
import com.jewelbox.player.data.net.RemoteDeviceDto
import com.jewelbox.player.data.resolveCover
import com.jewelbox.player.playback.RemoteCommands
import com.jewelbox.player.playback.livePositionSec
import com.jewelbox.player.playback.nextRepeatMode
import kotlinx.coroutines.delay

/**
 * The remote of another player: what it plays, live, and its controls —
 * those its capabilities allow. Its queue below; a tap plays that track there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen(
    deviceId: String,
    onBack: () -> Unit,
    onListenedHere: () -> Unit,
    vm: RemoteViewModel = viewModel(),
) {
    LaunchedEffect(deviceId) { vm.select(deviceId) }
    val devices by vm.devices.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val device = devices?.firstOrNull { it.deviceId == deviceId }
    CommandFailedToast(vm)
    ListenedHereEffect(vm, onListenedHere)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        device?.let { deviceName(it) } ?: stringResource(R.string.remote_devices_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (device == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                if (devices == null) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        stringResource(R.string.remote_device_gone),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
            return@Scaffold
        }

        val tracks = queue?.tracks.orEmpty()
        val currentIndex = device.state?.currentIndex ?: -1
        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item(key = "player") {
                RemotePlayer(device, serverUrl, vm::send)
            }
            if (device.state?.track != null) {
                item(key = "listen-here") {
                    OutlinedButton(
                        onClick = { vm.listenHere(deviceId) },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Icon(Icons.Filled.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.remote_listen_here))
                    }
                }
            }
            if ("queue" in device.capabilities && tracks.isNotEmpty()) {
                item(key = "queue-title") {
                    Text(
                        stringResource(R.string.remote_queue_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
                    )
                    HorizontalDivider()
                }
                itemsIndexed(tracks, key = { index, track -> "q-$index-${track.id}" }) { index, track ->
                    val current = index == currentIndex
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.send(RemoteCommands.playIndex(index)) }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                track.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (current) FontWeight.Bold else null,
                                color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                track.artistName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        track.duration?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemotePlayer(
    device: RemoteDeviceDto,
    serverUrl: String,
    send: (kotlinx.serialization.json.JsonObject, String?) -> Unit,
) {
    val state = device.state
    val track = state?.track
    val can = { capability: String -> capability in device.capabilities }
    val command = { body: kotlinx.serialization.json.JsonObject -> send(body, null) }

    // The position moves on between the reports (every 30 s while playing).
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            nowMs = System.currentTimeMillis()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val cover = resolveCover(serverUrl, track?.coverUrl)
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = track?.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Album,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.fillMaxSize(0.5f),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            track?.title ?: stringResource(R.string.remote_nothing_playing),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        track?.let {
            Text(
                it.artistName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (it.albumTitle.isNotBlank()) {
                Text(
                    it.albumTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Seek bar: the drag position while dragging, sent on release.
        if (state != null && track != null) {
            Spacer(Modifier.height(16.dp))
            var dragSec by remember { mutableStateOf<Float?>(null) }
            val duration = state.durationSec.toFloat().coerceAtLeast(1f)
            val position = (dragSec ?: livePositionSec(state, nowMs).toFloat()).coerceIn(0f, duration)
            val interactions = remember { MutableInteractionSource() }
            Slider(
                value = position,
                valueRange = 0f..duration,
                onValueChange = { dragSec = it },
                onValueChangeFinished = {
                    dragSec?.let { command(RemoteCommands.seek(it.toDouble())) }
                    dragSec = null
                },
                enabled = can("seek") && state.durationSec > 0,
                interactionSource = interactions,
                thumb = { SliderDefaults.Thumb(interactionSource = interactions, thumbSize = DpSize(14.dp, 14.dp)) },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(6.dp),
                        thumbTrackGapSize = 0.dp,
                        drawStopIndicator = null,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    formatSec(position),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatSec(state.durationSec.toFloat()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        val playing = state?.playbackState == "playing"
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (can("shuffle")) {
                val on = state?.shuffle == true
                IconButton(onClick = { command(RemoteCommands.shuffle(!on)) }) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = stringResource(R.string.shuffle),
                        tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { command(RemoteCommands.simple("previous")) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.previous), modifier = Modifier.size(40.dp))
            }
            FilledIconButton(onClick = { command(RemoteCommands.simple("toggle")) }, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = { command(RemoteCommands.simple("next")) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.next), modifier = Modifier.size(40.dp))
            }
            if (can("repeat")) {
                val repeat = state?.repeat ?: "off"
                IconButton(onClick = { command(RemoteCommands.repeat(nextRepeatMode(repeat))) }) {
                    Icon(
                        if (repeat == "one") Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = stringResource(
                            when (repeat) {
                                "all" -> R.string.repeat_all
                                "one" -> R.string.repeat_one
                                else -> R.string.repeat_off
                            },
                        ),
                        tint = if (repeat != "off") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Volume: the player's own (0–100), sent on release.
        if (can("volume") && state?.volume != null) {
            Spacer(Modifier.height(8.dp))
            var dragVolume by remember { mutableStateOf<Float?>(null) }
            val muted = state.muted == true
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = { command(RemoteCommands.mute(!muted)) },
                    enabled = can("mute"),
                ) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(if (muted) R.string.remote_unmute else R.string.remote_mute),
                    )
                }
                Slider(
                    value = dragVolume ?: state.volume.toFloat(),
                    valueRange = 0f..100f,
                    onValueChange = { dragVolume = it },
                    onValueChangeFinished = {
                        dragVolume?.let { command(RemoteCommands.volume(it.toInt())) }
                        dragVolume = null
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun formatSec(seconds: Float): String {
    val total = seconds.toLong().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
