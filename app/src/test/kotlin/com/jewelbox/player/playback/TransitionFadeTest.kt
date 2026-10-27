package com.jewelbox.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitionFadeTest {

    @Test
    fun `rises at the start and falls at the end`() {
        assertEquals(0f, transitionFadeFactor(0, 200_000, 4, fadeIn = true, fadeOut = true))
        assertEquals(0.5f, transitionFadeFactor(2_000, 200_000, 4, fadeIn = true, fadeOut = true), 0.001f)
        assertEquals(1f, transitionFadeFactor(100_000, 200_000, 4, fadeIn = true, fadeOut = true))
        assertEquals(0.25f, transitionFadeFactor(199_000, 200_000, 4, fadeIn = true, fadeOut = true), 0.001f)
    }

    @Test
    fun `only where allowed, never when off or on a short track`() {
        assertEquals(1f, transitionFadeFactor(0, 200_000, 4, fadeIn = false, fadeOut = true))
        assertEquals(1f, transitionFadeFactor(199_000, 200_000, 4, fadeIn = true, fadeOut = false))
        assertEquals(1f, transitionFadeFactor(0, 200_000, 0, fadeIn = true, fadeOut = true))
        assertEquals(1f, transitionFadeFactor(6_000, 7_000, 4, fadeIn = false, fadeOut = true))
    }

    @Test
    fun `tracks that follow each other on an album`() {
        assertEquals(true, followsOnAlbum(1, 3, 1, 4))
        assertEquals(false, followsOnAlbum(1, 3, 1, 5))
        assertEquals(false, followsOnAlbum(1, 3, 2, 4))
        assertEquals(false, followsOnAlbum(null, 3, null, 4))
    }
}
