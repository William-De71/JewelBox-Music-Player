package com.jewelbox.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverUrlTest {

    private val server = "http://192.168.1.20:3001"

    @Test
    fun `null or blank cover yields null`() {
        assertNull(resolveCover(server, null))
        assertNull(resolveCover(server, ""))
        assertNull(resolveCover(server, "   "))
    }

    @Test
    fun `absolute urls pass through untouched`() {
        assertEquals(
            "https://coverartarchive.org/release/x/front-250",
            resolveCover(server, "https://coverartarchive.org/release/x/front-250"),
        )
        assertEquals("http://other/img.jpg", resolveCover(server, "http://other/img.jpg"))
        // Case-insensitive scheme check.
        assertEquals("HTTPS://a/b.png", resolveCover(server, "HTTPS://a/b.png"))
    }

    @Test
    fun `relative covers are prefixed with the server url`() {
        assertEquals(
            "http://192.168.1.20:3001/covers/abc123",
            resolveCover(server, "/covers/abc123"),
        )
    }

    @Test
    fun `slashes are normalized between server and path`() {
        assertEquals("http://s/covers/x", resolveCover("http://s/", "/covers/x"))
        assertEquals("http://s/covers/x", resolveCover("http://s", "covers/x"))
        assertEquals("http://s/covers/x", resolveCover("http://s/", "covers/x"))
    }
}
