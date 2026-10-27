package com.jewelbox.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayGainTest {

    @Test
    fun `turns the chosen gain into a volume multiplier`() {
        assertEquals(0.5f, replayGainFactor(-6.0206, -12.0412, ReplayGainMode.TRACK), 0.001f)
        assertEquals(0.25f, replayGainFactor(-6.0206, -12.0412, ReplayGainMode.ALBUM), 0.001f)
    }

    @Test
    fun `falls back on the other gain when one is missing`() {
        assertEquals(0.5f, replayGainFactor(-6.0206, null, ReplayGainMode.ALBUM), 0.001f)
        assertEquals(0.5f, replayGainFactor(null, -6.0206, ReplayGainMode.TRACK), 0.001f)
    }

    @Test
    fun `never goes above full volume`() {
        assertEquals(1f, replayGainFactor(4.0, 3.0, ReplayGainMode.ALBUM))
    }

    @Test
    fun `is neutral when off or untagged`() {
        assertEquals(1f, replayGainFactor(-6.0, -6.0, ReplayGainMode.OFF))
        assertEquals(1f, replayGainFactor(null, null, ReplayGainMode.ALBUM))
        assertEquals(1f, replayGainFactor(Double.NaN, null, ReplayGainMode.TRACK))
    }

    @Test
    fun `reads the stored mode, album by default`() {
        assertEquals(ReplayGainMode.TRACK, ReplayGainMode.fromKey("track"))
        assertEquals(ReplayGainMode.OFF, ReplayGainMode.fromKey("off"))
        assertEquals(ReplayGainMode.ALBUM, ReplayGainMode.fromKey(null))
        assertEquals(ReplayGainMode.ALBUM, ReplayGainMode.fromKey("bogus"))
    }
}
