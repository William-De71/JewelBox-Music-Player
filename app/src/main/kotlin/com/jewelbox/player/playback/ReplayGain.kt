package com.jewelbox.player.playback

import kotlin.math.pow

/**
 * Volume normalization from the files' ReplayGain tags (written by JewelBox
 * Ripper, read by the server's audio scan) — same rules as the PWA
 * (client/src/utils/replayGain.js).
 *
 * The gain rides on the player volume, which tops out at 1: a negative gain (a
 * loud master) lowers the track, a positive one (a quiet master) is left out
 * rather than pushing past full scale. [ALBUM] keeps the nuances between the
 * tracks of a disc; a missing gain falls back on the other one.
 */
enum class ReplayGainMode(val key: String) {
    OFF("off"),
    TRACK("track"),
    ALBUM("album");

    companion object {
        val DEFAULT = ALBUM

        fun fromKey(key: String?): ReplayGainMode = values().firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Volume multiplier (0–1] for a track: 1 when off, untagged, or louder. */
fun replayGainFactor(trackGain: Double?, albumGain: Double?, mode: ReplayGainMode): Float {
    val gain = when (mode) {
        ReplayGainMode.OFF -> null
        ReplayGainMode.TRACK -> trackGain ?: albumGain
        ReplayGainMode.ALBUM -> albumGain ?: trackGain
    }
    if (gain == null || !gain.isFinite()) return 1f
    return 10.0.pow(gain / 20).coerceAtMost(1.0).toFloat()
}
