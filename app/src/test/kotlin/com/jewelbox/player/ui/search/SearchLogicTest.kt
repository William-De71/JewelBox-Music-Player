package com.jewelbox.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchLogicTest {

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("daft", SearchLogic.normalizeQuery("  daft  "))
    }

    @Test
    fun `keeps a query of exactly the minimum length`() {
        assertEquals("ok", SearchLogic.normalizeQuery("ok"))
    }

    @Test
    fun `rejects empty blank and one-character queries`() {
        assertNull(SearchLogic.normalizeQuery(""))
        assertNull(SearchLogic.normalizeQuery("   "))
        assertNull(SearchLogic.normalizeQuery("a"))
        assertNull(SearchLogic.normalizeQuery("  a  "))
    }
}
