package com.jewelbox.player.data.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DTOs are the contract with the JewelBox server: these tests parse JSON
 * shaped exactly like the real responses (server/src/db/queries.js#mapAlbum).
 */
class DtoParsingTest {

    // Same configuration as ApiClient's converter.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `parses search results and defaults missing sections`() {
        val payload = """
        {
          "albums": [{"id": 1, "title": "Discovery", "artist": {"id": 3, "name": "Daft Punk"}}],
          "tracks": [
            {"id": 100, "position": 1, "title": "One More Time", "duration": "5:20",
             "has_file": true, "play_count": 2, "is_favorite": false,
             "album_id": 1, "album_title": "Discovery", "artist_name": "Daft Punk",
             "cover_url": "/covers/abc"}
          ]
        }
        """.trimIndent()

        val results = json.decodeFromString<SearchResultsDto>(payload)

        assertEquals(1, results.albums.size)
        assertEquals("Daft Punk", results.albums[0].artist.name)
        assertEquals(1, results.tracks.size)
        assertEquals("Discovery", results.tracks[0].albumTitle)
        assertTrue(results.tracks[0].hasFile)

        // A partial payload (older server, unexpected shape) must not crash.
        val empty = json.decodeFromString<SearchResultsDto>("{}")
        assertTrue(empty.albums.isEmpty())
        assertTrue(empty.tracks.isEmpty())
    }

    @Test
    fun `parses a full album with tracks`() {
        val payload = """
        {
          "id": 12, "title": "Discovery", "year": 2001, "genre": "Electro",
          "total_duration": "60:57", "ean": "0724384960650", "rating": 5,
          "cover_url": "/covers/abc", "notes": "Culte.",
          "is_lent": false, "lent_to": null, "lent_at": null,
          "is_wanted": false, "audio_folder": "Daft Punk/Discovery",
          "has_audio": true,
          "created_at": "2026-01-01", "updated_at": "2026-01-02",
          "artist": {"id": 3, "name": "Daft Punk"},
          "label": {"id": 9, "name": "Virgin"},
          "tracks": [
            {"id": 100, "position": 1, "title": "One More Time", "duration": "5:20",
             "has_file": true, "play_count": 12, "is_favorite": true},
            {"id": 101, "position": 2, "title": "Aerodynamic", "duration": null,
             "has_file": false, "play_count": 0, "is_favorite": false}
          ]
        }
        """.trimIndent()

        val album = json.decodeFromString<AlbumDto>(payload)

        assertEquals(12, album.id)
        assertEquals("Daft Punk", album.artist.name)
        assertEquals("Virgin", album.label?.name)
        assertEquals("60:57", album.totalDuration)
        assertEquals("/covers/abc", album.coverUrl)
        assertTrue(album.hasAudio)
        assertEquals(2, album.tracks.size)
        assertTrue(album.tracks[0].hasFile)
        assertTrue(album.tracks[0].isFavorite)
        assertEquals(12, album.tracks[0].playCount)
        assertFalse(album.tracks[1].hasFile)
        assertNull(album.tracks[1].duration)
    }

    @Test
    fun `parses a minimal album as returned in list responses`() {
        // List responses have no tracks and may carry nulls.
        val payload = """
        {
          "id": 1, "title": "Sans étiquette",
          "artist": {"id": 2, "name": "Inconnu"},
          "label": null, "year": null, "genre": null, "rating": null, "cover_url": null
        }
        """.trimIndent()

        val album = json.decodeFromString<AlbumDto>(payload)

        assertNull(album.label)
        assertNull(album.year)
        assertNull(album.coverUrl)
        assertFalse(album.hasAudio)
        assertTrue(album.tracks.isEmpty())
    }

    @Test
    fun `unknown server fields are ignored`() {
        val payload = """
        {
          "id": 1, "title": "T", "artist": {"id": 1, "name": "A"},
          "some_future_field": {"nested": true}
        }
        """.trimIndent()

        assertEquals("T", json.decodeFromString<AlbumDto>(payload).title)
    }

    @Test
    fun `parses a paginated albums page`() {
        val payload = """
        {
          "data": [
            {"id": 1, "title": "A", "artist": {"id": 1, "name": "X"}},
            {"id": 2, "title": "B", "artist": {"id": 1, "name": "X"}}
          ],
          "pagination": {"total": 57, "page": 2, "limit": 24, "totalPages": 3}
        }
        """.trimIndent()

        val page = json.decodeFromString<AlbumsPage>(payload)

        assertEquals(2, page.data.size)
        assertEquals(57, page.pagination.total)
        assertEquals(3, page.pagination.totalPages)
    }

    @Test
    fun `parses the health response`() {
        assertEquals("ok", json.decodeFromString<HealthDto>("""{"status":"ok"}""").status)
    }

    @Test
    fun `parses every dto type on its own`() {
        assertEquals("X", json.decodeFromString<ArtistDto>("""{"id":1,"name":"X"}""").name)
        assertEquals("L", json.decodeFromString<LabelDto>("""{"id":2,"name":"L"}""").name)
        assertEquals(
            7,
            json.decodeFromString<TrackDto>("""{"id":7,"title":"T"}""").id,
        )
        assertEquals(
            4,
            json.decodeFromString<Pagination>("""{"total":90,"page":4,"limit":24,"totalPages":4}""").page,
        )
    }

    @Test
    fun `request bodies serialize to the snake_case the server expects`() {
        assertEquals(
            """{"track_id":42}""",
            json.encodeToString(NowPlayingBody.serializer(), NowPlayingBody(42)),
        )
        assertEquals(
            """{"track_id":42,"started_at":1000}""",
            json.encodeToString(ScrobbleBody.serializer(), ScrobbleBody(42, 1000)),
        )
    }

    @Test
    fun `dto defaults are safe when fields are absent`() {
        // Constructed directly (as UI code might) the defaults must match the
        // server's semantics: nothing played, nothing favorite, no file.
        val track = TrackDto(id = 1, title = "T")
        assertEquals(0, track.position)
        assertFalse(track.hasFile)
        assertFalse(track.isFavorite)
        assertEquals(0, track.playCount)
        assertNull(track.duration)

        val album = AlbumDto(id = 1, title = "A", artist = ArtistDto(1, "X"), label = LabelDto(2, "L"))
        assertFalse(album.hasAudio)
        assertTrue(album.tracks.isEmpty())
        assertEquals("L", album.label?.name)

        val page = AlbumsPage()
        assertTrue(page.data.isEmpty())
        assertEquals(1, page.pagination.page)
        assertEquals(24, Pagination().limit)
        assertEquals("", HealthDto().status)
    }
}
