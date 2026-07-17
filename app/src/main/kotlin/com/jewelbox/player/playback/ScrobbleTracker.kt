package com.jewelbox.player.playback

/**
 * Pure bookkeeping for Last.fm scrobbling, mirroring the PWA's rules
 * (client/src/components/PlayerContext.jsx):
 *  - a track only scrobbles if it lasts at least 30 seconds;
 *  - it scrobbles once the listener actually heard half of it, or 4 minutes;
 *  - position jumps (seeks, |dt| >= 2s between ticks) don't count as listening;
 *  - each (re)start of a track re-arms a single scrobble.
 *
 * No Android or player dependency: [onTrackStarted] on every track change, then
 * [onTick] about once per second with the playback position; a non-null result
 * means "fire the scrobble now" (returned exactly once per started track).
 */
class ScrobbleTracker {

    /** What to send to the server when the threshold is crossed. */
    data class Scrobble(val trackId: Int, val startedAt: Long)

    private class State(val trackId: Int, val startedAt: Long) {
        var played = 0.0     // seconds actually listened
        var lastTime = 0.0   // last observed position, in seconds
        var fired = false
    }

    private var state: State? = null

    /** Call on every media item transition; null clears the tracking. */
    fun onTrackStarted(trackId: Int?, nowEpochSeconds: Long = System.currentTimeMillis() / 1000) {
        state = trackId?.let { State(it, nowEpochSeconds) }
    }

    /**
     * Feed the current position/duration (seconds). Returns the scrobble to fire
     * when the threshold is crossed for the first time, null otherwise.
     */
    fun onTick(positionSeconds: Double, durationSeconds: Double): Scrobble? {
        val s = state ?: return null

        val dt = positionSeconds - s.lastTime
        if (dt > 0 && dt < 2) s.played += dt
        s.lastTime = positionSeconds

        if (s.fired || durationSeconds < 30) return null
        if (s.played < durationSeconds / 2 && s.played < 240) return null

        s.fired = true
        return Scrobble(s.trackId, s.startedAt)
    }
}
