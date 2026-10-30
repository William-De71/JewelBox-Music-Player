package com.jewelbox.player.data.offline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.ArtistDto
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The offline catalog and pending plays must survive an app restart (a new
 * store reads back what the previous one wrote), and a damaged file must read
 * as empty rather than crash the app at start-up.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineStoreTest {

    private lateinit var context: Context
    private val dir get() = File(context.filesDir, "offline")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir.deleteRecursively()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val album = OfflineAlbum(
        scope = "server/",
        serverUrl = "http://192.168.1.20:3001",
        album = AlbumDto(id = 12, title = "Kind of Blue", artist = ArtistDto(1, "Miles Davis")),
        addedAt = 1000L,
    )

    @Test
    fun `starts empty without any file`() {
        val store = OfflineStore(context)

        assertTrue(store.catalog.value.albums.isEmpty())
        assertTrue(store.pendingPlays.value.isEmpty())
        assertEquals(File(dir, "covers"), store.coversDir)
    }

    @Test
    fun `the catalog is kept across a restart`() = runTest {
        val store = OfflineStore(context)

        val next = store.updateCatalog { it.withAlbum(album) }

        assertEquals(listOf(album), next.albums)
        assertEquals(next, store.catalog.value)
        assertEquals(listOf(album), OfflineStore(context).catalog.value.albums)
    }

    @Test
    fun `pending plays are kept across a restart`() = runTest {
        val store = OfflineStore(context)
        val play = PendingPlay(trackId = 5, startedAt = 100L, playedAtMs = 130_000L)

        store.updatePendingPlays { enqueuePlay(it, play) }

        assertEquals(listOf(play), store.pendingPlays.value)
        assertEquals(listOf(play), OfflineStore(context).pendingPlays.value)
    }

    @Test
    fun `rewrites over a previous file`() = runTest {
        OfflineStore(context).updateCatalog { it.withAlbum(album) }

        OfflineStore(context).updateCatalog { it.withoutAlbum(album.scope, album.album.id) }

        assertTrue(OfflineStore(context).catalog.value.albums.isEmpty())
    }

    @Test
    fun `a damaged file reads as empty`() {
        dir.mkdirs()
        File(dir, "catalog.json").writeText("{not json")
        File(dir, "pending-plays.json").writeText("[")

        val store = OfflineStore(context)

        assertTrue(store.catalog.value.albums.isEmpty())
        assertTrue(store.pendingPlays.value.isEmpty())
    }
}
