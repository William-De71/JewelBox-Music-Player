package com.jewelbox.player.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Remote control by another client of the server (Gladys…): the pure logic,
 * unit-tested, behind [RemoteControl].
 *
 * The server (>= 1.18) relays the commands on a Server-Sent Events stream
 * (GET /api/player/remote/events) the app keeps open while "Allow remote
 * control" is on. In return the player reports its live state (PATCH
 * /api/player/queue/state with playback_state…) at every change, setting on
 * or off: that is what the controller shows. Same protocol as the PWA's
 * utils/remoteControl.js and the desktop's core/remote.py.
 */

/** What this player supports beyond play / pause / next / previous (no mute: the phone has its own). */
val REMOTE_CAPABILITIES = listOf("seek", "volume", "shuffle", "repeat", "queue", "play_media", "append")

/** While playing, a report at least this often: without a stream the server forgets a player silent for 90 s. */
const val LIVE_HEARTBEAT_SEC = 30.0

/** A position this far from the extrapolated one is a seek (same threshold as the Gladys SDK). */
private const val SEEK_TOLERANCE_SEC = 3.0

/** Splits a text/event-stream into (event, JSON data) pairs, line by line. */
class SseParser {
    private var event = "message"
    private val data = StringBuilder()

    /** Feeds one line (without its line break); returns the event it completes, or null. */
    fun line(raw: String): Pair<String, JsonObject>? {
        val line = raw.removeSuffix("\r")
        if (line.isEmpty()) {
            val done = if (data.isEmpty()) null else parse(event, data.toString())
            event = "message"
            data.setLength(0)
            return done
        }
        if (line.startsWith(":")) return null
        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
    }

    private fun parse(event: String, text: String): Pair<String, JsonObject>? =
        runCatching { event to Json.parseToJsonElement(text).jsonObject }.getOrNull()
}

/**
 * The live state the server expects (PATCH /api/player/queue/state). No
 * default values: the Retrofit converter drops a field equal to its default,
 * and the server must get every one of them (shuffle off included).
 */
@Serializable
data class LiveState(
    @SerialName("playback_state") val playbackState: String,
    @SerialName("track_id") val trackId: Int?,
    @SerialName("current_index") val currentIndex: Int,
    @SerialName("position_sec") val positionSec: Double,
    @SerialName("duration_sec") val durationSec: Double,
    val volume: Int,
    val shuffle: Boolean,
    val repeat: String,
    @SerialName("device_label") val deviceLabel: String,
)

/** Builds the live state from the player's values (volume 0–1, positions in ms). */
fun liveStateOf(
    hasItem: Boolean,
    isPlaying: Boolean,
    trackId: Int?,
    currentIndex: Int,
    positionMs: Long,
    durationMs: Long,
    volume: Float,
    shuffle: Boolean,
    repeat: RepeatMode,
    deviceLabel: String,
): LiveState = LiveState(
    playbackState = when {
        !hasItem -> "idle"
        isPlaying -> "playing"
        else -> "paused"
    },
    trackId = if (hasItem) trackId else null,
    currentIndex = if (hasItem) currentIndex else -1,
    positionSec = (positionMs.coerceAtLeast(0) / 100L) / 10.0,
    durationSec = (durationMs.coerceAtLeast(0) / 100L) / 10.0,
    volume = (volume.coerceIn(0f, 1f) * 100).roundToInt(),
    shuffle = shuffle,
    repeat = repeat.name.lowercase(),
    deviceLabel = deviceLabel,
)

/**
 * When to report: every change of what a controller shows (play / pause,
 * track, volume, modes…), a seek, and every [LIVE_HEARTBEAT_SEC] while
 * playing. The player ticks every second: everything else is ignored.
 */
class LiveReporter {
    private var last: LiveState? = null
    private var at = 0.0

    /** The next update reports whatever it is (the stream just opened). */
    fun forget() {
        last = null
    }

    fun update(live: LiveState, nowSec: Double): Boolean {
        val previous = last
        if (previous == null) {
            last = live
            at = nowSec
            return true
        }
        val shown = { s: LiveState -> s.copy(positionSec = 0.0, durationSec = 0.0) }
        var changed = shown(live) != shown(previous)
        if (!changed && live.playbackState == "playing") {
            val expected = previous.positionSec + (nowSec - at)
            changed = abs(live.positionSec - expected) > SEEK_TOLERANCE_SEC || nowSec - at >= LIVE_HEARTBEAT_SEC
        } else if (!changed) {
            changed = abs(live.positionSec - previous.positionSec) > SEEK_TOLERANCE_SEC
        }
        if (changed) {
            last = live
            at = nowSec
        }
        return changed
    }
}

/** Seconds before reconnection attempt n° [attempt] (1, 2…): 2, 4, 8… capped at 60. */
fun reconnectDelaySec(attempt: Int): Long = minOf(60L, 1L shl attempt.coerceIn(1, 6))
