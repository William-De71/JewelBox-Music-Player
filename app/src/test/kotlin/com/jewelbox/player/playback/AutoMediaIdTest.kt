package com.jewelbox.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoMediaIdTest {

    @Test
    fun `every id reads back as itself`() {
        val ids = listOf(
            AutoMediaId.Root,
            AutoMediaId.Home,
            AutoMediaId.Library,
            AutoMediaId.Playlists,
            AutoMediaId.Downloads,
            AutoMediaId.Artist(7),
            AutoMediaId.Album(12),
            AutoMediaId.Playlist(3),
            AutoMediaId.Smart("dynamic_mix"),
            AutoMediaId.DownloadedAlbum("5f0c-uuid/Rock", 12),
            AutoMediaId.DownloadedPlaylist("5f0c-uuid/", 3),
        )
        ids.forEach { assertEquals(it, AutoMediaId.parse(it.value)) }
    }

    @Test
    fun `a scope may hold colons`() {
        val id = AutoMediaId.DownloadedAlbum("server/Jazz: live", 4)
        assertEquals("dl-album:4:server/Jazz: live", id.value)
        assertEquals(id, AutoMediaId.parse(id.value))
    }

    @Test
    fun `rejects what is not one of ours`() {
        listOf(
            "", "12", "unknown", "album:", "album:x", "artist:1.5", "playlist:",
            "smart:", "dl-album:4", "dl-album:4:", "dl-playlist:x:scope", "other:1",
        ).forEach { assertNull(it, AutoMediaId.parse(it)) }
    }

    @Test
    fun `pages through a list`() {
        val items = (1..10).toList()
        assertEquals(listOf(1, 2, 3, 4), pageOf(items, 0, 4))
        assertEquals(listOf(9, 10), pageOf(items, 2, 4))
        assertEquals(emptyList<Int>(), pageOf(items, 3, 4))
        assertEquals(items, pageOf(items, 0, Int.MAX_VALUE))
        assertEquals(emptyList<Int>(), pageOf(items, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `no paging asked means everything`() {
        val items = listOf("a", "b")
        assertEquals(items, pageOf(items, -1, 10))
        assertEquals(items, pageOf(items, 0, 0))
    }
}
