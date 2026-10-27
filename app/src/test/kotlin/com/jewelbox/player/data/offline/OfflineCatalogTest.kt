package com.jewelbox.player.data.offline

import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.ArtistDto
import com.jewelbox.player.data.net.PlaylistDto
import com.jewelbox.player.data.net.QueueTrackDto
import com.jewelbox.player.data.net.TrackDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCatalogTest {

    private val scope = scopeOf("srv-1", "will_cd")
    private val other = scopeOf("srv-1", "jazz")

    private fun album(id: Int, vararg tracks: Pair<Int, Boolean>) = AlbumDto(
        id = id,
        title = "Album $id",
        artist = ArtistDto(1, "Artist"),
        tracks = tracks.map { (trackId, file) -> TrackDto(id = trackId, title = "T$trackId", hasFile = file) },
    )

    private fun playlist(id: Int, vararg trackIds: Int) = PlaylistDto(
        id = id,
        name = "P$id",
        tracks = trackIds.map { QueueTrackDto(id = it, title = "T$it", hasFile = true) },
    )

    @Test
    fun `keys hold the collection, so two collections never mix`() {
        assertEquals("srv-1/will_cd#track:7", cacheKey(scope, 7))
        assertTrue(cacheKey(scope, 7) != cacheKey(other, 7))
        assertEquals("srv-1/", scopeOf("srv-1", null))
    }

    @Test
    fun `wanted tracks are the playable ones, once each`() {
        val catalog = OfflineCatalog()
            .withAlbum(OfflineAlbum(scope, "http://a", album(1, 10 to true, 11 to false, 12 to true)))
            .withPlaylist(OfflinePlaylist(scope, "http://a", playlist(5, 12, 13, 12)))
            .withAlbum(OfflineAlbum(other, "http://a", album(1, 10 to true)))
        val wanted = wantedTracks(catalog)
        assertEquals(
            setOf(cacheKey(scope, 10), cacheKey(scope, 12), cacheKey(scope, 13), cacheKey(other, 10)),
            wanted.keys,
        )
        assertEquals(13, wanted.getValue(cacheKey(scope, 13)).trackId)
    }

    @Test
    fun `catalog edits replace by collection and id`() {
        var catalog = OfflineCatalog().withAlbum(OfflineAlbum(scope, "u", album(1, 10 to true)))
        catalog = catalog.withAlbum(OfflineAlbum(scope, "u", album(1, 10 to true, 11 to true)))
        assertEquals(1, catalog.albums.size)
        assertEquals(2, catalog.album(scope, 1)!!.album.tracks.size)
        assertNull(catalog.album(other, 1))
        assertEquals(0, catalog.withoutAlbum(scope, 1).albums.size)
        catalog = catalog.withPlaylist(OfflinePlaylist(scope, "u", playlist(5, 1)))
        assertEquals(0, catalog.withoutPlaylist(scope, 5).playlists.size)
    }

    @Test
    fun `reconcile adds the missing and drops the unwanted`() {
        val wanted = mapOf("a" to OfflineTrack("a", "u", 1), "b" to OfflineTrack("b", "u", 2))
        val plan = reconcile(wanted, setOf("b", "z", "c"))
        assertEquals(listOf("a"), plan.toAdd.map { it.key })
        assertEquals(listOf("c", "z"), plan.toRemove)
    }

    @Test
    fun `item status from its tracks`() {
        val keys = listOf("a", "b")
        assertEquals(ItemStatus.NotDownloaded, itemStatus(false, keys, emptyMap()))
        assertEquals(ItemStatus.Downloading(0f, true), itemStatus(true, keys, emptyMap()))
        val half = itemStatus(
            true, keys,
            mapOf("a" to TrackProgress(TrackState.DONE, 100f), "b" to TrackProgress(TrackState.DOWNLOADING, 50f)),
        ) as ItemStatus.Downloading
        assertEquals(0.75f, half.fraction, 0.001f)
        assertEquals(false, half.waiting)
        assertEquals(
            ItemStatus.Downloaded,
            itemStatus(true, keys, keys.associateWith { TrackProgress(TrackState.DONE) }),
        )
        assertEquals(
            ItemStatus.Failed(1),
            itemStatus(true, keys, mapOf("a" to TrackProgress(TrackState.FAILED))),
        )
        assertEquals(ItemStatus.Downloaded, itemStatus(true, emptyList(), emptyMap()))
    }

    @Test
    fun `keys of an album and a playlist`() {
        assertEquals(listOf(cacheKey(scope, 10)), albumKeys(scope, album(1, 10 to true, 11 to false)))
        assertEquals(listOf(cacheKey(scope, 12), cacheKey(scope, 13)), playlistKeys(scope, playlist(5, 12, 13, 12)))
    }

    @Test
    fun `pending plays keep their date and drop old scrobbles`() {
        val play = PendingPlay(trackId = 7, startedAt = 1_000_000L, playedAtMs = 1_793_140_200_000L)
        assertEquals("2026-10-27T22:30:00Z", play.playedAtIso())
        assertEquals(false, play.scrobbleExpired(1_000_000L + SCROBBLE_MAX_AGE_SEC))
        assertEquals(true, play.scrobbleExpired(1_000_001L + SCROBBLE_MAX_AGE_SEC))
        val full = (1..PENDING_PLAYS_MAX).map { PendingPlay(it, 0, 0) }
        val next = enqueuePlay(full, play)
        assertEquals(PENDING_PLAYS_MAX, next.size)
        assertEquals(2, next.first().trackId)
        assertEquals(play, next.last())
    }
}
