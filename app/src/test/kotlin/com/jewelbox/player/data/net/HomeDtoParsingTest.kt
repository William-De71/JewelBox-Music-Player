package com.jewelbox.player.data.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract with GET /api/player/home (server >= 1.9). Payloads mirror what the
 * server actually sends (server/src/db/queries.js#getRecentPlayedItems): recent
 * entries carry either an album or a playlist, never both.
 */
class HomeDtoParsingTest {

    // Same configuration as ApiClient's converter.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `parses a mixed recent section and suggestions`() {
        val payload = """
        {
          "recent": [
            {"item_type": "album", "played_at": "2026-07-19 10:00:00",
             "album": {"id": 1, "title": "OK Computer", "rating": 5,
                       "cover_url": "/covers/okc.jpg", "has_audio": true,
                       "artist": {"id": 3, "name": "Radiohead"}}},
            {"item_type": "playlist", "played_at": "2026-07-18 21:12:00",
             "playlist": {"id": 7, "name": "Mes titres", "track_count": 14,
                          "total_duration_seconds": 3120, "cover_url": "/covers/ab.jpg"}}
          ],
          "suggestions": [
            {"id": 22, "title": "Nevermind", "artist": {"id": 4, "name": "Nirvana"}}
          ]
        }
        """.trimIndent()

        val home = json.decodeFromString<HomeDto>(payload)

        assertEquals(2, home.recent.size)
        val albumEntry = home.recent[0]
        assertEquals("album", albumEntry.itemType)
        assertEquals("OK Computer", albumEntry.album?.title)
        assertEquals("Radiohead", albumEntry.album?.artist?.name)
        assertTrue(albumEntry.album?.hasAudio == true)
        // An album entry carries no playlist, and vice versa.
        assertNull(albumEntry.playlist)

        val playlistEntry = home.recent[1]
        assertEquals("playlist", playlistEntry.itemType)
        assertEquals("Mes titres", playlistEntry.playlist?.name)
        assertEquals(14, playlistEntry.playlist?.trackCount)
        assertEquals("/covers/ab.jpg", playlistEntry.playlist?.coverUrl)
        assertNull(playlistEntry.album)

        assertEquals(1, home.suggestions.size)
        assertEquals("Nevermind", home.suggestions[0].title)
    }

    @Test
    fun `defaults every section of an empty or partial payload`() {
        val empty = json.decodeFromString<HomeDto>("{}")
        assertTrue(empty.recent.isEmpty())
        assertTrue(empty.suggestions.isEmpty())

        // Fresh install: no history yet, but the server still suggests albums.
        val noHistory = json.decodeFromString<HomeDto>(
            """{"recent": [], "suggestions": [{"id": 1, "title": "A", "artist": {"id": 1, "name": "B"}}]}""",
        )
        assertTrue(noHistory.recent.isEmpty())
        assertEquals(1, noHistory.suggestions.size)
    }

    @Test
    fun `parses a playlist without a cover`() {
        // An empty playlist has no track to borrow a cover from: the server sends null.
        val payload = """
        {"recent": [{"item_type": "playlist", "played_at": "2026-07-19 08:00:00",
                     "playlist": {"id": 9, "name": "Vide", "track_count": 0,
                                  "total_duration_seconds": 0, "cover_url": null}}],
         "suggestions": []}
        """.trimIndent()

        val home = json.decodeFromString<HomeDto>(payload)

        assertNull(home.recent[0].playlist?.coverUrl)
        assertEquals(0, home.recent[0].playlist?.trackCount)
    }

    @Test
    fun `tolerates a malformed recent entry carrying neither album nor playlist`() {
        // The screen skips such entries rather than crashing the whole feed.
        val home = json.decodeFromString<HomeDto>(
            """{"recent": [{"item_type": "album", "played_at": "2026-07-19 10:00:00"}], "suggestions": []}""",
        )

        assertEquals(1, home.recent.size)
        assertNull(home.recent[0].album)
        assertNull(home.recent[0].playlist)
    }

    @Test
    fun `serializes the play-history body with the server's field names`() {
        val encoded = json.encodeToString(PlayHistoryBody.serializer(), PlayHistoryBody("playlist", 42))

        assertEquals("""{"item_type":"playlist","item_id":42}""", encoded)
    }

    @Test
    fun `keeps parsing the plain playlist list that omits cover_url`() {
        // GET /api/playlists doesn't send cover_url; the added field must stay optional.
        val payload = """{"data": [{"id": 1, "name": "Rock", "track_count": 3,
                                    "total_duration_seconds": 600}]}"""

        val response = json.decodeFromString<PlaylistsResponse>(payload)

        assertEquals(1, response.data.size)
        assertNull(response.data[0].coverUrl)
        assertEquals(3, response.data[0].trackCount)
    }
}
