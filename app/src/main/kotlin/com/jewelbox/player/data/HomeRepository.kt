package com.jewelbox.player.data

import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.ApiClient
import com.jewelbox.player.data.net.HomeDto
import com.jewelbox.player.data.net.PlayHistoryBody
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Data layer for the home screen. Same pattern as [AlbumRepository]: the server
 * URL is re-read from [ServerPrefs] on each call so a change in Settings takes
 * effect immediately.
 */
class HomeRepository(private val prefs: ServerPrefs) {

    /** Emits the configured server URL and every later change to it. */
    val serverUrl: Flow<String> = prefs.serverUrl

    suspend fun currentServerUrl(): String = prefs.serverUrl.first()

    private suspend fun requireApi() =
        ApiClient.create(currentServerUrl())

    /** GET /api/player/home — recent plays + suggested albums (server >= 1.9). */
    suspend fun home(): HomeDto =
        requireApi().home()

    /** The [count] most recently added owned albums, newest first (home "latest" row). */
    suspend fun latestAlbums(count: Int = 5): List<AlbumDto> =
        requireApi().albums(page = 1, limit = count, sort = "created_at", order = "desc").data

    /** POST /api/player/history for an album or playlist, keyed by [itemId]. */
    suspend fun reportPlay(itemType: String, itemId: Int) =
        requireApi().reportPlay(PlayHistoryBody(itemType, itemId = itemId))

    /** POST /api/player/history for a smart playlist, keyed by its text [key]. */
    suspend fun reportSmartPlay(key: String) =
        requireApi().reportPlay(PlayHistoryBody("smart", itemKey = key))
}
