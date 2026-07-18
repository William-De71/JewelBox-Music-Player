package com.jewelbox.player.data

import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.AlbumsPage
import com.jewelbox.player.data.net.ApiClient
import com.jewelbox.player.data.net.HealthDto
import com.jewelbox.player.data.net.NowPlayingBody
import com.jewelbox.player.data.net.ScrobbleBody
import com.jewelbox.player.data.net.SearchResultsDto
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

    /** GET /api/player/search?q= — albums, artists and track titles (server >= 1.7). */
    suspend fun search(query: String): SearchResultsDto =
        requireApi().search(query)

    /** POST /api/player/tracks/:id/played — local play counter, independent of Last.fm. */
    suspend fun markPlayed(trackId: Int) =
        requireApi().markPlayed(trackId)

    /** POST /api/lastfm/nowplaying — the Last.fm session lives server-side. */
    suspend fun nowPlaying(trackId: Int) =
        requireApi().nowPlaying(NowPlayingBody(trackId))

    /** POST /api/lastfm/scrobble */
    suspend fun scrobble(trackId: Int, startedAt: Long) =
        requireApi().scrobble(ScrobbleBody(trackId, startedAt))
}
