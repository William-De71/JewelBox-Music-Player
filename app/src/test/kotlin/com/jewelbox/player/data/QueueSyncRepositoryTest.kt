package com.jewelbox.player.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jewelbox.player.playback.LiveState
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The server-side queue is scoped by X-Device-Id: every call must carry this
 * install's stable id, and hit the routes the desktop and web clients use.
 */
@RunWith(RobolectricTestRunner::class)
class QueueSyncRepositoryTest {

    private lateinit var context: Context
    private lateinit var prefs: ServerPrefs
    private lateinit var repo: QueueSyncRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = ServerPrefs(context)
        repo = QueueSyncRepository(prefs)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "datastore").deleteRecursively()
    }

    private val queueJson = """
        {"device_id":"phone","device_label":"Pixel","current_index":1,"position_sec":42.5,
         "updated_at":"2026-10-17 20:00:00",
         "tracks":[{"entry_id":1,"id":5,"title":"So What","has_file":true},
                   {"entry_id":2,"id":6,"title":"Blue in Green","has_file":true}]}
    """.trimIndent()

    @Test
    fun `saveQueue puts the tracks with this device's id`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(queueJson))
            server.start()
            prefs.setServerUrl(server.url("/").toString())

            repo.saveQueue(listOf(5, 6), 1, 12.5, "JewelBox Android · Pixel", "Kind of Blue")

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/player/queue", request.path)
            assertEquals(prefs.deviceId(), request.getHeader("X-Device-Id"))
            val body = request.body.readUtf8()
            assertTrue("\"track_ids\":[5,6]" in body)
            assertTrue("\"current_index\":1" in body)
            assertTrue("\"position_sec\":12.5" in body)
            assertTrue("\"device_label\":\"JewelBox Android · Pixel\"" in body)
            assertTrue("\"source_label\":\"Kind of Blue\"" in body)
        }
    }

    @Test
    fun `updateState patches the progress only`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()
            prefs.setServerUrl(server.url("/").toString())

            repo.updateState(2, 30.0)

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/player/queue/state", request.path)
            assertEquals("""{"current_index":2,"position_sec":30.0}""", request.body.readUtf8())
        }
    }

    @Test
    fun `clear deletes this device's queue`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()
            prefs.setServerUrl(server.url("/").toString())

            repo.clear()

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/player/queue", request.path)
        }
    }

    @Test
    fun `otherDevices lists the queues left elsewhere`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"data":[{"device_id":"desk","device_label":"JewelBox bureau · pc",
                        "current_index":3,"updated_at":"2026-10-17 20:00:00","track_count":12}]}""",
                ),
            )
            server.start()
            prefs.setServerUrl(server.url("/").toString())

            val devices = repo.otherDevices()

            assertEquals("/api/player/queue/devices", server.takeRequest().path)
            assertEquals("desk", devices.single().deviceId)
            assertEquals(12, devices.single().trackCount)
        }
    }

    @Test
    fun `deviceQueue reads another device's queue`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(queueJson))
            server.start()
            prefs.setServerUrl(server.url("/").toString())

            val queue = repo.deviceQueue("phone")

            assertEquals("/api/player/queue/devices/phone", server.takeRequest().path)
            assertEquals(42.5, queue.positionSec, 0.0)
            assertEquals(listOf(5, 6), queue.tracks.map { it.id })
        }
    }

    @Test
    fun `the device id is stable across calls`() = runTest {
        val first = prefs.deviceId()
        assertTrue(first.isNotBlank())
        assertEquals(first, prefs.deviceId())
    }

    @Test
    fun `ownQueue reads this device's queue`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(queueJson))
            server.start()
            prefs.setServerUrl(server.url("/").toString())

            val queue = repo.ownQueue()

            assertEquals("/api/player/queue", server.takeRequest().path)
            assertEquals(1, queue.currentIndex)
        }
    }

    @Test
    fun `track fetches a single queue-shaped track`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":5,"title":"So What","has_file":true}"""))
            server.start()
            prefs.setServerUrl(server.url("/").toString())

            val track = repo.track(5)

            assertEquals("/api/player/tracks/5", server.takeRequest().path)
            assertEquals("So What", track.title)
        }
    }

    @Test
    fun `radio asks around an album or an artist`() = runTest {
        MockWebServer().use { server ->
            val radioJson = """{"seed":{"name":"Kind of Blue"},"tracks":[{"id":5,"title":"So What"}]}"""
            server.enqueue(MockResponse().setBody(radioJson))
            server.enqueue(MockResponse().setBody("""{"tracks":[]}"""))
            server.start()
            prefs.setServerUrl(server.url("/").toString())

            val radio = repo.radio(albumId = 12)
            repo.radio(artistId = 3)

            assertEquals("/api/player/radio?album_id=12", server.takeRequest().path)
            assertEquals("/api/player/radio?artist_id=3", server.takeRequest().path)
            assertEquals("Kind of Blue", radio.seed?.name)
            assertEquals(listOf(5), radio.tracks.map { it.id })
        }
    }

    @Test
    fun `reportLiveState sends every field`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()
            prefs.setServerUrl(server.url("/").toString())

            repo.reportLiveState(
                LiveState("playing", 5, 0, 1.5, 300.0, 80, false, "off", "JewelBox Android · Pixel", null),
            )

            val body = server.takeRequest().body.readUtf8()
            assertTrue("\"playback_state\":\"playing\"" in body)
            assertTrue("\"shuffle\":false" in body)
        }
    }
}
