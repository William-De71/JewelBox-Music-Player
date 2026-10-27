package com.jewelbox.player.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Sleep timer: playback pauses after a delay, fading out over the last
 * seconds, or at the end of the current track (no fade: the track ends by
 * itself). Same rules as the PWA (client/src/utils/sleepTimer.js) and the
 * desktop player; the remote controller (Gladys) sets it with the
 * sleep_timer command.
 */
sealed interface SleepTimer {
    /** Wall-clock end, so it can be reported as a date. */
    data class Timed(val endsAtMs: Long) : SleepTimer

    data object EndOfTrack : SleepTimer
}

val SLEEP_TIMER_MINUTES = listOf(15, 30, 45, 60, 90)
const val SLEEP_FADE_MS = 10_000L

/** The remote command's shape: minutes (0 cancels) or end of track. */
fun sleepTimerFrom(minutes: Int?, endOfTrack: Boolean, nowMs: Long): SleepTimer? = when {
    endOfTrack -> SleepTimer.EndOfTrack
    minutes != null && minutes > 0 -> SleepTimer.Timed(nowMs + minutes * 60_000L)
    else -> null
}

/** Volume multiplier while the timer runs out: 1, then down to 0 over the fade. */
fun sleepFadeFactor(timer: SleepTimer?, nowMs: Long): Float {
    if (timer !is SleepTimer.Timed) return 1f
    val left = timer.endsAtMs - nowMs
    if (left >= SLEEP_FADE_MS) return 1f
    return (left.toFloat() / SLEEP_FADE_MS).coerceAtLeast(0f)
}

/** What the live state reports: only the set field is sent (defaults are dropped). */
@Serializable
data class LiveSleepTimer(
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("end_of_track") val endOfTrack: Boolean? = null,
)

fun sleepTimerLive(timer: SleepTimer?): LiveSleepTimer? = when (timer) {
    null -> null
    SleepTimer.EndOfTrack -> LiveSleepTimer(endOfTrack = true)
    is SleepTimer.Timed -> LiveSleepTimer(endsAt = Instant.ofEpochMilli(timer.endsAtMs).toString())
}
