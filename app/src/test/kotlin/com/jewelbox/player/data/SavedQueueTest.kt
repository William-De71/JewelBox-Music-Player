package com.jewelbox.player.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved queue is what makes playback resume after the app is closed, so its
 * serialization has to survive a round-trip and tolerate whatever an older (or
 * newer) build wrote into the store.
 */
class SavedQueueTest {

    // Same configuration as PlaybackStateStore's.
    private val json = Json { ignoreUnknownKeys = true }

    private fun roundTrip(queue: SavedQueue): SavedQueue =
        json.decodeFromString(
            SavedQueue.serializer(),
            json.encodeToString(SavedQueue.serializer(), queue),
        )

    @Test
    fun `round-trips a full album queue with its position`() {
        val queue = SavedQueue(
            serverUrl = "http://192.168.1.10:3001/",
            tracks = listOf(
                SavedTrack(1, "Airbag", "Radiohead", "OK Computer", "/covers/okc.jpg", false),
                SavedTrack(2, "Paranoid Android", "Radiohead", "OK Computer", "/covers/okc.jpg", true),
            ),
            index = 1,
            positionMs = 65_000L,
            sourceType = "album",
            sourceId = "12",
        )

        val restored = roundTrip(queue)

        assertEquals(queue, restored)
        assertEquals(2, restored.tracks.size)
        assertEquals("Paranoid Android", restored.tracks[1].title)
        assertTrue(restored.tracks[1].isFavorite)
        // Position and index are what "resume where I left off" actually means.
        assertEquals(1, restored.index)
        assertEquals(65_000L, restored.positionMs)
    }

    @Test
    fun `round-trips a dynamic mix queue`() {
        val queue = SavedQueue(
            serverUrl = "http://host:3001/",
            tracks = listOf(SavedTrack(9, "T", "A", "Al")),
            sourceType = "smart",
            sourceId = "dynamic_mix",
            dynamicMix = true,
        )

        val restored = roundTrip(queue)

        assertTrue(restored.dynamicMix)
        assertEquals("dynamic_mix", restored.sourceId)
        assertEquals("smart", restored.sourceType)
    }

    @Test
    fun `defaults optional fields when they are absent`() {
        // A queue started from search has no source; covers may be missing too.
        val payload = """
        {"server_url": "http://h:3001/",
         "tracks": [{"id": 5, "title": "T", "artist_name": "A", "album_title": "Al"}]}
        """.trimIndent()

        val restored = json.decodeFromString(SavedQueue.serializer(), payload)

        assertEquals(0, restored.index)
        assertEquals(0L, restored.positionMs)
        assertNull(restored.sourceType)
        assertNull(restored.sourceId)
        assertFalse(restored.dynamicMix)
        assertNull(restored.tracks[0].coverUrl)
        assertFalse(restored.tracks[0].isFavorite)
    }

    @Test
    fun `ignores unknown fields written by another build`() {
        val payload = """
        {"server_url": "http://h:3001/", "tracks": [], "future_field": 42,
         "tracks_extra": {"nested": true}}
        """.trimIndent()

        val restored = json.decodeFromString(SavedQueue.serializer(), payload)

        assertEquals("http://h:3001/", restored.serverUrl)
        assertTrue(restored.tracks.isEmpty())
    }

    @Test
    fun `uses snake_case field names so the payload stays readable`() {
        val encoded = json.encodeToString(
            SavedQueue.serializer(),
            SavedQueue(
                serverUrl = "http://h:3001/",
                tracks = listOf(SavedTrack(1, "T", "A", "Al", "/c.jpg", true)),
                positionMs = 500L,
                sourceType = "playlist",
                sourceId = "3",
            ),
        )

        assertTrue(encoded.contains("\"server_url\""))
        assertTrue(encoded.contains("\"position_ms\""))
        assertTrue(encoded.contains("\"source_type\""))
        assertTrue(encoded.contains("\"artist_name\""))
        assertTrue(encoded.contains("\"is_favorite\""))
    }
}
