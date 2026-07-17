package com.jewelbox.player.data.net

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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
}
