package com.jewelbox.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScrobbleTrackerTest {

    private val tracker = ScrobbleTracker()

    /** Simulates [seconds] of real playback with 1s ticks starting at [from]. */
    private fun play(from: Double, seconds: Int, duration: Double): ScrobbleTracker.Scrobble? {
        var result: ScrobbleTracker.Scrobble? = null
        for (i in 1..seconds) {
            tracker.onTick(from + i, duration)?.let { result = it }
        }
        return result
    }

    @Test
    fun `no scrobble without a started track`() {
        assertNull(tracker.onTick(10.0, 300.0))
    }

    @Test
    fun `track shorter than 30s never scrobbles`() {
        tracker.onTrackStarted(1, nowEpochSeconds = 1000)
        assertNull(play(0.0, 25, duration = 25.0))
    }

    @Test
    fun `scrobbles once half of the track was listened`() {
        tracker.onTrackStarted(7, nowEpochSeconds = 1000)
        // 60s track: nothing during the first 29 listened seconds…
        assertNull(play(0.0, 29, duration = 60.0))
        // …scrobble exactly when reaching 30s listened.
        val scrobble = tracker.onTick(30.0, 60.0)
        assertNotNull(scrobble)
        assertEquals(7, scrobble!!.trackId)
        assertEquals(1000L, scrobble.startedAt)
    }

    @Test
    fun `long track scrobbles at 4 minutes even before half`() {
        tracker.onTrackStarted(2, nowEpochSeconds = 1000)
        // 600s track, half would be 300s: the 240s cap wins.
        assertNull(play(0.0, 239, duration = 600.0))
        assertNotNull(play(239.0, 1, duration = 600.0))
    }

    @Test
    fun `seek jumps do not count as listening`() {
        tracker.onTrackStarted(3, nowEpochSeconds = 1000)
        assertNull(play(0.0, 10, duration = 120.0))       // 10s listened
        assertNull(tracker.onTick(100.0, 120.0))          // seek +90s: ignored
        // 100→149: 49 more listened seconds = 59 total, threshold is 60.
        assertNull(play(100.0, 49, duration = 120.0))
        assertNotNull(play(149.0, 1, duration = 120.0))   // 60th second → scrobble
    }

    @Test
    fun `backward seek does not count and does not break accounting`() {
        tracker.onTrackStarted(4, nowEpochSeconds = 1000)
        assertNull(play(0.0, 20, duration = 80.0))        // 20s listened
        assertNull(tracker.onTick(5.0, 80.0))             // seek back: ignored
        assertNull(play(5.0, 19, duration = 80.0))        // 39s listened
        assertNotNull(play(24.0, 1, duration = 80.0))     // 40s = half → scrobble
    }

    @Test
    fun `paused playback does not accumulate`() {
        tracker.onTrackStarted(5, nowEpochSeconds = 1000)
        assertNull(play(0.0, 20, duration = 60.0))
        repeat(100) { assertNull(tracker.onTick(20.0, 60.0)) }  // paused at 20s
        assertNull(play(20.0, 9, duration = 60.0))
        assertNotNull(play(29.0, 1, duration = 60.0))
    }

    @Test
    fun `fires exactly once per started track`() {
        tracker.onTrackStarted(6, nowEpochSeconds = 1000)
        assertNotNull(play(0.0, 30, duration = 60.0))
        assertNull(play(30.0, 30, duration = 60.0))       // rest of the track: silent
    }

    @Test
    fun `restarting the same track re-arms the scrobble`() {
        tracker.onTrackStarted(8, nowEpochSeconds = 1000)
        assertNotNull(play(0.0, 30, duration = 60.0))
        // Repeat-one: the transition restarts the track.
        tracker.onTrackStarted(8, nowEpochSeconds = 2000)
        val again = play(0.0, 30, duration = 60.0)
        assertNotNull(again)
        assertEquals(2000L, again!!.startedAt)
    }

    @Test
    fun `clearing the track stops tracking`() {
        tracker.onTrackStarted(9, nowEpochSeconds = 1000)
        tracker.onTrackStarted(null)
        assertNull(play(0.0, 60, duration = 60.0))
    }
}
