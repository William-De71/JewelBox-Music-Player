package com.jewelbox.player.data.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientTest {

    // --- normalize() ---------------------------------------------------------

    @Test
    fun `normalize adds http scheme when missing`() {
        assertEquals("http://192.168.1.20:3001/", ApiClient.normalize("192.168.1.20:3001"))
    }

    @Test
    fun `normalize keeps an explicit scheme`() {
        assertEquals("http://s:3001/", ApiClient.normalize("http://s:3001"))
        assertEquals("https://s:3001/", ApiClient.normalize("https://s:3001"))
    }

    @Test
    fun `normalize adds the trailing slash retrofit requires`() {
        assertEquals("http://s/", ApiClient.normalize("http://s"))
        assertEquals("http://s/", ApiClient.normalize("http://s/"))
    }

    @Test
    fun `normalize trims whitespace`() {
        assertEquals("http://s:3001/", ApiClient.normalize("  http://s:3001  "))
    }

    @Test
    fun `normalize rejects a blank url`() {
        assertThrows(IllegalArgumentException::class.java) { ApiClient.normalize("   ") }
    }

    // --- create() against a real HTTP server ---------------------------------

    @Test
    fun `created api calls the server and parses the response`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
            server.start()

            val api = ApiClient.create(server.url("/").toString())
            val health = api.health()

            assertEquals("ok", health.status)
            assertEquals("/api/health", server.takeRequest().path)
        }
    }

    @Test
    fun `albums request carries the collection filters`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"data":[],"pagination":{"total":0,"page":1,"limit":24,"totalPages":0}}"""
                )
            )
            server.start()

            val api = ApiClient.create(server.url("/").toString())
            api.albums(page = 2, limit = 50)

            val path = server.takeRequest().path.orEmpty()
            assertTrue(path.startsWith("/api/albums?"))
            assertTrue("page=2" in path)
            assertTrue("limit=50" in path)
            assertTrue("sort=artist" in path)
            assertTrue("order=asc" in path)
            assertTrue("wanted=false" in path)
        }
    }

    @Test
    fun `albums defaults request the sorted owned collection`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[],"pagination":{}}"""))
            server.start()

            val api = ApiClient.create(server.url("/").toString())
            api.albums()   // all defaults

            val path = server.takeRequest().path.orEmpty()
            assertTrue("page=1" in path)
            assertTrue("limit=24" in path)
            assertTrue("wanted=false" in path)
        }
    }

    @Test
    fun `nowplaying and played hit their endpoints`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()

            val api = ApiClient.create(server.url("/").toString())
            api.nowPlaying(NowPlayingBody(trackId = 5))
            api.markPlayed(5)

            val nowPlaying = server.takeRequest()
            assertEquals("/api/lastfm/nowplaying", nowPlaying.path)
            assertTrue("\"track_id\":5" in nowPlaying.body.readUtf8())
            assertEquals("/api/player/tracks/5/played", server.takeRequest().path)
        }
    }

    @Test
    fun `scrobble posts a snake_case json body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()

            val api = ApiClient.create(server.url("/").toString())
            api.scrobble(ScrobbleBody(trackId = 42, startedAt = 1234567890L))

            val request = server.takeRequest()
            assertEquals("/api/lastfm/scrobble", request.path)
            val body = request.body.readUtf8()
            assertTrue("\"track_id\":42" in body)
            assertTrue("\"started_at\":1234567890" in body)
        }
    }
}
