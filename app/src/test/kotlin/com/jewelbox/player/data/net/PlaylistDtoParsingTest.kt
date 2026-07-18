package com.jewelbox.player.data.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the playlist / smart-playlist / favorite DTOs. The JSON
 * payloads mirror the real server responses (server/src/db/queries.js
 * #mapQueueTrack/#getPlaylists and the server's route handlers).
 */
class PlaylistDtoParsingTest {

    // Same configuration as ApiClient's converter.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `parses the playlists listing`() {
        val payload = """
        {
          "data": [
            {"id": 1, "name": "Route", "created_at": "2026-07-01 10:00:00",
             "updated_at": "2026-07-15 08:30:00", "track_count": 12,
             "total_duration_seconds": 3723},
            {"id": 2, "name": "Vide", "created_at": "2026-07-02 10:00:00",
             "updated_at": "2026-07-02 10:00:00", "track_count": 0,
             "total_duration_seconds": 0}
          ]
        }
        """.trimIndent()

        val res = json.decodeFromString<PlaylistsResponse>(payload)

        assertEquals(2, res.data.size)
        assertEquals("Route", res.data[0].name)
        assertEquals(12, res.data[0].trackCount)
        assertEquals(3723, res.data[0].totalDurationSeconds)
        assertEquals(0, res.data[1].trackCount)
    }

    @Test
    fun `parses a playlist detail with entry ids and queue-shaped tracks`() {
        val payload = """
        {
          "id": 1, "name": "Route",
          "created_at": "2026-07-01 10:00:00", "updated_at": "2026-07-15 08:30:00",
          "tracks": [
            {"entry_id": 55, "position": 1, "id": 100, "title": "One More Time",
             "duration": "5:20", "has_file": true, "play_count": 12, "is_favorite": true,
             "album_id": 12, "album_title": "Discovery", "artist_name": "Daft Punk",
             "cover_url": "/covers/abc"},
            {"entry_id": 56, "position": 2, "id": 200, "title": "Sans fichier",
             "duration": null, "has_file": false, "play_count": 0, "is_favorite": false,
             "album_id": 13, "album_title": "B", "artist_name": "X", "cover_url": null}
          ]
        }
        """.trimIndent()

        val playlist = json.decodeFromString<PlaylistDto>(payload)

        assertEquals("Route", playlist.name)
        assertEquals(2, playlist.tracks.size)
        val first = playlist.tracks[0]
        assertEquals(55, first.entryId)
        assertEquals(100, first.id)
        assertEquals("Daft Punk", first.artistName)
        assertEquals("Discovery", first.albumTitle)
        assertTrue(first.isFavorite)
        assertTrue(first.hasFile)
        assertFalse(playlist.tracks[1].hasFile)
        assertNull(playlist.tracks[1].coverUrl)
        // Absent outside of the add-tracks response.
        assertEquals(0, playlist.added)
    }

    @Test
    fun `parses the add-tracks response with its added count`() {
        val payload = """
        {"id": 1, "name": "Route", "tracks": [], "added": 9}
        """.trimIndent()

        assertEquals(9, json.decodeFromString<PlaylistDto>(payload).added)
    }

    @Test
    fun `parses the smart playlists listing`() {
        val payload = """
        {
          "data": [
            {"key": "newest", "track_count": 100},
            {"key": "favourites", "track_count": 7},
            {"key": "dynamic_mix", "track_count": 50}
          ]
        }
        """.trimIndent()

        val res = json.decodeFromString<SmartPlaylistsResponse>(payload)

        assertEquals(3, res.data.size)
        assertEquals("favourites", res.data[1].key)
        assertEquals(7, res.data[1].trackCount)
    }

    @Test
    fun `parses a smart playlist detail (no entry ids there)`() {
        val payload = """
        {
          "key": "favourites",
          "tracks": [
            {"position": 1, "id": 100, "title": "T", "duration": "3:45",
             "has_file": true, "play_count": 3, "is_favorite": true,
             "album_id": 12, "album_title": "A", "artist_name": "X",
             "cover_url": null}
          ]
        }
        """.trimIndent()

        val smart = json.decodeFromString<SmartPlaylistDto>(payload)

        assertEquals("favourites", smart.key)
        assertEquals(1, smart.tracks.size)
        assertNull(smart.tracks[0].entryId)
    }

    @Test
    fun `parses the dynamic mix played response`() {
        val payload = """
        {
          "removed": true,
          "tracks": [
            {"position": 1, "id": 7, "title": "T", "duration": "3:45",
             "has_file": true, "play_count": 0, "is_favorite": false,
             "album_id": 1, "album_title": "A", "artist_name": "X", "cover_url": null}
          ]
        }
        """.trimIndent()

        val res = json.decodeFromString<DynamicMixPlayedDto>(payload)

        assertTrue(res.removed)
        assertEquals(listOf(7), res.tracks.map { it.id })
    }

    @Test
    fun `request bodies serialize to the snake_case the server expects`() {
        assertEquals(
            """{"name":"Mes favoris"}""",
            json.encodeToString(CreatePlaylistBody.serializer(), CreatePlaylistBody("Mes favoris")),
        )
        assertEquals(
            """{"track_id":42}""",
            json.encodeToString(AddTracksBody.serializer(), AddTracksBody(trackId = 42)),
        )
        assertEquals(
            """{"album_id":12}""",
            json.encodeToString(AddTracksBody.serializer(), AddTracksBody(albumId = 12)),
        )
        assertEquals(
            """{"entry_ids":[3,1,2]}""",
            json.encodeToString(ReorderBody.serializer(), ReorderBody(listOf(3, 1, 2))),
        )
        assertEquals(
            """{"is_favorite":true}""",
            json.encodeToString(FavoriteBody.serializer(), FavoriteBody(true)),
        )
        assertEquals(
            """{"track_id":9}""",
            json.encodeToString(DynamicMixPlayedBody.serializer(), DynamicMixPlayedBody(9)),
        )
    }

    @Test
    fun `dto defaults are safe when fields are absent`() {
        val track = QueueTrackDto(id = 1, title = "T")
        assertNull(track.entryId)
        assertFalse(track.hasFile)
        assertFalse(track.isFavorite)
        assertEquals("", track.artistName)
        assertEquals("", track.albumTitle)
        assertNull(track.coverUrl)

        assertTrue(PlaylistsResponse().data.isEmpty())
        assertTrue(SmartPlaylistsResponse().data.isEmpty())
        assertTrue(SmartPlaylistDto().tracks.isEmpty())
        assertFalse(DynamicMixPlayedDto().removed)
        assertEquals(0, PlaylistSummaryDto(id = 1, name = "P").trackCount)
    }
}
