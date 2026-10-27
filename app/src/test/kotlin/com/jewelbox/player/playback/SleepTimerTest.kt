package com.jewelbox.player.playback

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {

    @Test
    fun `reads the remote command shape`() {
        assertEquals(SleepTimer.Timed(1000 + 30 * 60_000L), sleepTimerFrom(30, false, 1000))
        assertEquals(SleepTimer.EndOfTrack, sleepTimerFrom(5, true, 1000))
        assertNull(sleepTimerFrom(0, false, 1000))
        assertNull(sleepTimerFrom(null, false, 1000))
    }

    @Test
    fun `fades over the last seconds only`() {
        val timer = SleepTimer.Timed(100_000)
        assertEquals(1f, sleepFadeFactor(timer, 100_000 - SLEEP_FADE_MS - 1))
        assertEquals(0.5f, sleepFadeFactor(timer, 100_000 - SLEEP_FADE_MS / 2), 0.001f)
        assertEquals(0f, sleepFadeFactor(timer, 100_001))
        assertEquals(1f, sleepFadeFactor(SleepTimer.EndOfTrack, 0))
        assertEquals(1f, sleepFadeFactor(null, 0))
    }

    @Test
    fun `reports only the set field`() {
        assertNull(sleepTimerLive(null))
        assertEquals("""{"end_of_track":true}""", Json.encodeToString(sleepTimerLive(SleepTimer.EndOfTrack)))
        assertEquals(
            """{"ends_at":"2026-10-27T22:30:00Z"}""",
            Json.encodeToString(sleepTimerLive(SleepTimer.Timed(1_793_140_200_000))),
        )
    }

    @Test
    fun `the live state carries the timer, null included`() {
        val live = liveStateOf(true, true, 7, 0, 0, 0, 1f, false, RepeatMode.OFF, "Pixel", SleepTimer.EndOfTrack)
        assertEquals(LiveSleepTimer(endOfTrack = true), live.sleepTimer)
        val none = Json.encodeToString(liveStateOf(true, true, 7, 0, 0, 0, 1f, false, RepeatMode.OFF, "Pixel"))
        assertTrue(none, none.contains("\"sleep_timer\":null"))
    }
}
