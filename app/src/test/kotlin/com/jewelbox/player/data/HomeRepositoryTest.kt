package com.jewelbox.player.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * The repository reads the server URL from [ServerPrefs] on every call, so a
 * change in Settings takes effect without restarting. These tests drive it
 * against a real HTTP server through the real preference store.
 */
@RunWith(RobolectricTestRunner::class)
class HomeRepositoryTest {

    private lateinit var context: Context
    private lateinit var prefs: ServerPrefs
    private lateinit var repo: HomeRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = ServerPrefs(context)
        repo = HomeRepository(prefs)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "datastore").deleteRecursively()
    }

    @Test
    fun `home fetches the feed from the configured server`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"recent":[{"item_type":"playlist","played_at":"2026-07-19 10:00:00",
                                "playlist":{"id":7,"name":"Mes titres","track_count":3,
                                            "total_duration_seconds":600,"cover_url":"/c.jpg"}}],
                     "suggestions":[{"id":22,"title":"Nevermind",
                                     "artist":{"id":4,"name":"Nirvana"}}]}
                    """.trimIndent(),
                ),
            )
            server.start()
            prefs.setServerUrl(server.url("/").toString())

            val home = repo.home()

            assertEquals("/api/player/home", server.takeRequest().path)
            assertEquals("Mes titres", home.recent[0].playlist?.name)
            assertEquals("Nevermind", home.suggestions[0].title)
        }
    }

    @Test
    fun `reportPlay posts the item to the history endpoint`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()
            prefs.setServerUrl(server.url("/").toString())

            repo.reportPlay("playlist", 42)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/player/history", request.path)
            val body = request.body.readUtf8()
            assertTrue("\"item_type\":\"playlist\"" in body)
            assertTrue("\"item_id\":42" in body)
        }
    }

    @Test
    fun `picks up a server url changed in settings between calls`() = runTest {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                first.enqueue(MockResponse().setBody("""{"recent":[],"suggestions":[]}"""))
                second.enqueue(MockResponse().setBody("""{"recent":[],"suggestions":[]}"""))
                first.start()
                second.start()

                prefs.setServerUrl(first.url("/").toString())
                repo.home()
                // The user edits the address in Settings: the next call must go
                // to the new server, with no restart and no stale Retrofit client.
                prefs.setServerUrl(second.url("/").toString())
                repo.home()

                assertEquals(1, first.requestCount)
                assertEquals(1, second.requestCount)
            }
        }
    }

    @Test
    fun `currentServerUrl reflects what was stored`() = runTest {
        prefs.setServerUrl("http://192.168.1.50:3001")

        // ServerPrefs trims; ApiClient is what appends the trailing slash.
        assertEquals("http://192.168.1.50:3001", repo.currentServerUrl())
    }

    @Test
    fun `surfaces a 404 from a server too old to know the endpoint`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            prefs.setServerUrl(server.url("/").toString())

            val error = assertThrows(HttpException::class.java) {
                runBlocking { repo.home() }
            }

            assertEquals(404, error.code())
        }
    }
}
