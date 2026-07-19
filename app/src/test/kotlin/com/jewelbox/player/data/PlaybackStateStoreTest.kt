package com.jewelbox.player.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Exercises the real DataStore on Robolectric's JVM Android: what the app
 * writes when playback moves must be exactly what it reads back on the next
 * start, which is the whole point of the resume feature.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackStateStoreTest {

    private lateinit var context: Context
    private lateinit var store: PlaybackStateStore

    private fun queue(
        index: Int = 0,
        positionMs: Long = 0L,
        tracks: List<SavedTrack> = listOf(
            SavedTrack(1, "Airbag", "Radiohead", "OK Computer", "/covers/okc.jpg"),
            SavedTrack(2, "Paranoid Android", "Radiohead", "OK Computer", "/covers/okc.jpg", true),
        ),
    ) = SavedQueue(
        serverUrl = "http://192.168.1.10:3001/",
        tracks = tracks,
        index = index,
        positionMs = positionMs,
        sourceType = "album",
        sourceId = "12",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = PlaybackStateStore(context)
    }

    @After
    fun tearDown() {
        // DataStore keeps one file per name for the whole process: clear it so
        // each test starts from a genuinely empty store.
        File(context.filesDir, "datastore").deleteRecursively()
    }

    @Test
    fun `returns null when nothing was ever saved`() = runTest {
        assertNull(store.load())
    }

    @Test
    fun `saves a queue and reads it back unchanged`() = runTest {
        val saved = queue(index = 1, positionMs = 65_000L)

        store.save(saved)

        assertEquals(saved, store.load())
    }

    @Test
    fun `overwrites the previous queue rather than appending`() = runTest {
        store.save(queue(index = 0, positionMs = 1_000L))
        store.save(queue(index = 1, positionMs = 42_000L))

        val restored = store.load()

        assertEquals(1, restored?.index)
        assertEquals(42_000L, restored?.positionMs)
    }

    @Test
    fun `clear removes the saved queue`() = runTest {
        store.save(queue())

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun `preserves the details each queue entry needs to be replayed`() = runTest {
        store.save(queue(index = 1, positionMs = 30_000L))

        val restored = store.load()!!

        assertEquals("http://192.168.1.10:3001/", restored.serverUrl)
        assertEquals("album", restored.sourceType)
        assertEquals("12", restored.sourceId)
        assertEquals(2, restored.tracks.size)
        val second = restored.tracks[1]
        assertEquals(2, second.id)
        assertEquals("Paranoid Android", second.title)
        assertEquals("Radiohead", second.artistName)
        assertEquals("OK Computer", second.albumTitle)
        assertEquals("/covers/okc.jpg", second.coverUrl)
        assertTrue(second.isFavorite)
    }

    @Test
    fun `round-trips a dynamic mix queue`() = runTest {
        store.save(
            SavedQueue(
                serverUrl = "http://h:3001/",
                tracks = listOf(SavedTrack(9, "T", "A", "Al")),
                sourceType = "smart",
                sourceId = "dynamic_mix",
                dynamicMix = true,
            ),
        )

        val restored = store.load()!!

        assertTrue(restored.dynamicMix)
        assertEquals("dynamic_mix", restored.sourceId)
    }

    @Test
    fun `an empty queue survives the round-trip`() = runTest {
        // PlayerConnection clears instead of saving an empty queue, but a store
        // holding one must still load rather than blow up.
        store.save(SavedQueue(serverUrl = "http://h:3001/", tracks = emptyList()))

        val restored = store.load()

        assertTrue(restored!!.tracks.isEmpty())
    }
}
