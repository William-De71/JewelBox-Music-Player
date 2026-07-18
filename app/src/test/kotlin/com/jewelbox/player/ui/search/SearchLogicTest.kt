package com.jewelbox.player.ui.search

import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

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

    // --- isServerTooOld() ----------------------------------------------------

    private fun httpException(code: Int) = HttpException(
        Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun `an http 404 means the server predates the endpoint`() {
        assertTrue(SearchLogic.isServerTooOld(httpException(404)))
    }

    @Test
    fun `a json decoding failure means the spa fallback answered instead`() {
        // Servers < 1.7 reply 200 + index.html on the unknown route.
        assertTrue(SearchLogic.isServerTooOld(SerializationException("Unexpected JSON token at offset 0")))
    }

    @Test
    fun `other failures are ordinary errors`() {
        assertFalse(SearchLogic.isServerTooOld(httpException(500)))
        assertFalse(SearchLogic.isServerTooOld(IOException("timeout")))
        assertFalse(SearchLogic.isServerTooOld(IllegalStateException("boom")))
    }
}
