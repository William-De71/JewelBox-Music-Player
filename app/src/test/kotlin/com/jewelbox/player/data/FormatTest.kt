package com.jewelbox.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `duration formats like the PWA`() {
        assertEquals("—", formatDurationSeconds(0))
        assertEquals("—", formatDurationSeconds(-5))
        assertEquals("0 min", formatDurationSeconds(45)) // under a minute
        assertEquals("3 min", formatDurationSeconds(225))
        assertEquals("1 h 02 min", formatDurationSeconds(3723))
        assertEquals("2 h 00 min", formatDurationSeconds(7200))
    }

    @Test
    fun `sqlite timestamps render as short french dates`() {
        assertEquals("17 juil. 2026", formatUpdatedAt("2026-07-17 21:03:00"))
        assertEquals("01 janv. 2026", formatUpdatedAt("2026-01-01"))
    }

    @Test
    fun `bad dates fall back to a dash`() {
        assertEquals("—", formatUpdatedAt(null))
        assertEquals("—", formatUpdatedAt(""))
        assertEquals("—", formatUpdatedAt("pas une date"))
    }
}
