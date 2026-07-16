package com.jewelbox.player.data

import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.AlbumsPage
import com.jewelbox.player.data.net.ApiClient
import com.jewelbox.player.data.net.HealthDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Thin data layer over the JewelBox API. Reads the current server URL from
 * [ServerPrefs] on each call so a URL change in Settings takes effect immediately,
 * without any long-lived Retrofit instance to invalidate.
 */
class AlbumRepository(private val prefs: ServerPrefs) {

    /** Emits the configured server URL and every later change to it. */
    val serverUrl: Flow<String> = prefs.serverUrl

    private suspend fun requireApi() =
        ApiClient.create(currentServerUrl())

    suspend fun currentServerUrl(): String = prefs.serverUrl.first()

    /** Pings GET /api/health. Returns true only for {"status":"ok"}. */
    suspend fun health(explicitUrl: String? = null): Boolean {
        val url = explicitUrl ?: currentServerUrl()
        val api = ApiClient.create(url)
        val res: HealthDto = api.health()
        return res.status.equals("ok", ignoreCase = true)
    }

    /** GET /api/albums?page=&limit= */
    suspend fun albums(page: Int = 1, limit: Int = 24): AlbumsPage =
        requireApi().albums(page = page, limit = limit)

    /** GET /api/albums/:id — full album with its tracks. */
    suspend fun album(id: Int): AlbumDto =
        requireApi().album(id)
}
