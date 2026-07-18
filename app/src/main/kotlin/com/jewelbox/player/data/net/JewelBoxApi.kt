package com.jewelbox.player.data.net

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit surface for the endpoints consumed in this first iteration.
 * Paths are relative to the server base URL (which ends with "/").
 */
interface JewelBoxApi {

    @GET("api/health")
    suspend fun health(): HealthDto

    // Sorted by artist then year ascending: the server adds "a.year ASC" as the
    // secondary key whenever sort=artist, so this one call gives artist → date order.
    // wanted=false restricts to the owned collection, excluding the wishlist
    // (is_wanted=1) — those are CDs the user doesn't have yet.
    @GET("api/albums")
    suspend fun albums(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 24,
        @Query("sort") sort: String = "artist",
        @Query("order") order: String = "asc",
        @Query("wanted") wanted: String = "false",
    ): AlbumsPage

    @GET("api/albums/{id}")
    suspend fun album(@Path("id") id: Int): AlbumDto

    /** Local play counter (play_count/last_played_at), independent of Last.fm. */
    @POST("api/player/tracks/{id}/played")
    suspend fun markPlayed(@Path("id") id: Int)

    // Both Last.fm calls are fire-and-forget on the server side: it replies 204
    // even when scrobbling is unavailable, so playback is never disturbed.
    @POST("api/lastfm/nowplaying")
    suspend fun nowPlaying(@Body body: NowPlayingBody)

    @POST("api/lastfm/scrobble")
    suspend fun scrobble(@Body body: ScrobbleBody)

    // ── Playlists (user-defined) ─────────────────────────────────────────────

    @GET("api/playlists")
    suspend fun playlists(): PlaylistsResponse

    @POST("api/playlists")
    suspend fun createPlaylist(@Body body: CreatePlaylistBody): PlaylistDto

    @GET("api/playlists/{id}")
    suspend fun playlist(@Path("id") id: Int): PlaylistDto

    @PATCH("api/playlists/{id}")
    suspend fun renamePlaylist(@Path("id") id: Int, @Body body: CreatePlaylistBody): PlaylistDto

    @DELETE("api/playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: Int)

    /** Appends one track (track_id) or a whole album (album_id) at the end. */
    @POST("api/playlists/{id}/tracks")
    suspend fun addToPlaylist(@Path("id") id: Int, @Body body: AddTracksBody): PlaylistDto

    @DELETE("api/playlists/{id}/tracks/{entryId}")
    suspend fun removePlaylistEntry(@Path("id") id: Int, @Path("entryId") entryId: Int): PlaylistDto

    /** Full reorder: the body carries every entry id in the new order. */
    @PUT("api/playlists/{id}/tracks")
    suspend fun reorderPlaylist(@Path("id") id: Int, @Body body: ReorderBody): PlaylistDto

    // ── Smart playlists ──────────────────────────────────────────────────────

    @GET("api/smart-playlists")
    suspend fun smartPlaylists(): SmartPlaylistsResponse

    @GET("api/smart-playlists/{key}")
    suspend fun smartPlaylist(@Path("key") key: String): SmartPlaylistDto

    /** A dynamic mix track finished playing: the server rotates and refills the list. */
    @POST("api/smart-playlists/dynamic_mix/played")
    suspend fun dynamicMixPlayed(@Body body: DynamicMixPlayedBody): DynamicMixPlayedDto

    /** Discards the current mix and draws a completely new one (needs server >= 1.6). */
    @POST("api/smart-playlists/dynamic_mix/refresh")
    suspend fun dynamicMixRefresh(): SmartPlaylistDto

    // ── Recherche ────────────────────────────────────────────────────────────

    /**
     * Library search over album titles, artist names and track titles.
     * Requires server >= 1.7 (404 on older servers); q must be >= 2 characters.
     */
    @GET("api/player/search")
    suspend fun search(@Query("q") query: String): SearchResultsDto

    // ── Favoris ──────────────────────────────────────────────────────────────

    @PATCH("api/player/tracks/{id}/favorite")
    suspend fun setFavorite(@Path("id") id: Int, @Body body: FavoriteBody)
}
