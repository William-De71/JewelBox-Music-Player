package com.jewelbox.player.playback

import com.jewelbox.player.data.net.DeviceQueueDto
import com.jewelbox.player.data.net.RemoteDeviceDto
import com.jewelbox.player.data.net.RemoteDevicesResponse
import com.jewelbox.player.data.net.RemoteStateDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * This phone as a remote control of the other players (the desktop, the
 * PWA…): the pure logic, unit-tested, behind [RemoteWatch] and the remote
 * screens. The other half of [RemoteLogic.kt], where this phone is the one
 * being controlled. Server side: utils/remote.js.
 */

private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * The list after one event of GET /api/player/remote/watch: "devices" (the
 * whole list, sent first), "device" (one player added or changed), "gone"
 * (one player left). Anything else leaves the list as it is.
 */
fun applyWatchEvent(devices: List<RemoteDeviceDto>, event: String, data: JsonObject): List<RemoteDeviceDto> =
    runCatching {
        when (event) {
            "devices" -> json.decodeFromJsonElement<RemoteDevicesResponse>(data).data
            "device" -> {
                val device = json.decodeFromJsonElement<RemoteDeviceDto>(data)
                val index = devices.indexOfFirst { it.deviceId == device.deviceId }
                if (index < 0) devices + device else devices.toMutableList().also { it[index] = device }
            }
            "gone" -> {
                val id = data["device_id"]?.jsonPrimitive?.contentOrNull
                devices.filterNot { it.deviceId == id }
            }
            else -> devices
        }
    }.getOrDefault(devices)

/** The players this phone can drive: the others, with their command stream open. */
fun controllableOthers(devices: List<RemoteDeviceDto>, ownDeviceId: String?): List<RemoteDeviceDto> =
    devices.filter { it.controllable && it.deviceId != ownDeviceId }

/**
 * Where the player is now, in seconds: the reported position, moved on by the
 * time since the report while playing (players report only every 30 s then),
 * never past the end of the track.
 */
fun livePositionSec(state: RemoteStateDto, nowMs: Long): Double {
    if (state.playbackState != "playing") return state.positionSec
    val reportedMs = parseIsoMs(state.reportedAt) ?: return state.positionSec
    val elapsed = ((nowMs - reportedMs) / 1000.0).coerceAtLeast(0.0)
    val position = state.positionSec + elapsed
    return if (state.durationSec > 0) position.coerceAtMost(state.durationSec) else position
}

/**
 * "Listen here": the queue that player saved on the server, set to where it
 * is right now. Its live track and position are fresher than the saved ones
 * (saved every 30 s or so while playing); they stay when the live state
 * doesn't match that queue (a change not saved yet).
 */
fun takeOverQueue(queue: DeviceQueueDto, state: RemoteStateDto?, nowMs: Long): DeviceQueueDto {
    val trackId = state?.trackId ?: return queue
    val index = when (trackId) {
        queue.tracks.getOrNull(state.currentIndex)?.id -> state.currentIndex
        queue.tracks.getOrNull(queue.currentIndex)?.id -> queue.currentIndex
        else -> return queue
    }
    return queue.copy(currentIndex = index, positionSec = livePositionSec(state, nowMs))
}

private fun parseIsoMs(value: String?): Long? = try {
    value?.let { Instant.parse(it).toEpochMilli() }
} catch (e: DateTimeParseException) {
    null
}

/** The repeat mode a tap moves to: off → all → one → off. */
fun nextRepeatMode(current: String?): String = when (current) {
    "off", null -> "all"
    "all" -> "one"
    else -> "off"
}

/** What "Play on…" can send to another player (media.type of the play_media command). */
sealed interface RemoteMedia {
    data class Album(val id: Int) : RemoteMedia
    data class Playlist(val id: Int) : RemoteMedia
    data class Smart(val key: String) : RemoteMedia
}

/** The command bodies of POST /api/player/remote/devices/:id/commands. */
object RemoteCommands {
    fun simple(name: String): JsonObject = buildJsonObject { put("command", name) }

    fun seek(positionSec: Double): JsonObject = buildJsonObject {
        put("command", "seek")
        put("position_sec", positionSec.coerceAtLeast(0.0))
    }

    fun volume(volume: Int): JsonObject = buildJsonObject {
        put("command", "volume")
        put("volume", volume.coerceIn(0, 100))
    }

    fun mute(muted: Boolean): JsonObject = buildJsonObject {
        put("command", "mute")
        put("muted", muted)
    }

    fun shuffle(on: Boolean): JsonObject = buildJsonObject {
        put("command", "shuffle")
        put("shuffle", on)
    }

    fun repeat(mode: String): JsonObject = buildJsonObject {
        put("command", "repeat")
        put("repeat", mode)
    }

    fun playIndex(index: Int): JsonObject = buildJsonObject {
        put("command", "play_index")
        put("index", index)
    }

    fun playMedia(media: RemoteMedia): JsonObject = buildJsonObject {
        put("command", "play_media")
        put("mode", "replace")
        put("media", buildJsonObject {
            when (media) {
                is RemoteMedia.Album -> {
                    put("type", "album")
                    put("id", media.id)
                }
                is RemoteMedia.Playlist -> {
                    put("type", "playlist")
                    put("id", media.id)
                }
                is RemoteMedia.Smart -> {
                    put("type", "smart")
                    put("id", JsonPrimitive(media.key))
                }
            }
        })
    }
}
