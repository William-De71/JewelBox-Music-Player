package com.jewelbox.player.data.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

/**
 * The home endpoints as seen over the wire: right paths, right method, and the
 * failure an older server produces (which the UI turns into "update the server").
 */
class HomeApiTest {

    @Test
    fun `home calls the player home endpoint and parses both sections`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"recent":[{"item_type":"album","played_at":"2026-07-19 10:00:00",
                                "album":{"id":1,"title":"OK Computer",
                                         "artist":{"id":3,"name":"Radiohead"}}}],
                     "suggestions":[{"id":22,"title":"Nevermind",
                                     "artist":{"id":4,"name":"Nirvana"}}]}
                    """.trimIndent(),
                ),
            )
            server.start()

            val home = ApiClient.create(server.url("/").toString()).home()

            assertEquals("/api/player/home", server.takeRequest().path)
            assertEquals(1, home.recent.size)
            assertEquals("OK Computer", home.recent[0].album?.title)
            assertEquals(1, home.suggestions.size)
        }
    }

    @Test
    fun `reportPlay posts the item type and id`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()

            ApiClient.create(server.url("/").toString())
                .reportPlay(PlayHistoryBody("album", 12))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/player/history", request.path)
            val body = request.body.readUtf8()
            assertTrue("\"item_type\":\"album\"" in body)
            assertTrue("\"item_id\":12" in body)
        }
    }

    @Test
    fun `home on a server without the endpoint raises a 404`() = runBlocking {
        MockWebServer().use { server ->
            // Servers < 1.9 have no such route; SearchLogic.isServerTooOld maps
            // this (and an HTML body) to the "too old" state.
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()

            val api = ApiClient.create(server.url("/").toString())
            val error = assertThrows(HttpException::class.java) {
                runBlocking { api.home() }
            }

            assertEquals(404, error.code())
        }
    }
}
