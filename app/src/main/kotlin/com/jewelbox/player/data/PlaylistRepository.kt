package com.jewelbox.player.data

import com.jewelbox.player.data.net.AddTracksBody
import com.jewelbox.player.data.net.ApiClient
import com.jewelbox.player.data.net.CreatePlaylistBody
import com.jewelbox.player.data.net.DynamicMixPlayedBody
import com.jewelbox.player.data.net.DynamicMixPlayedDto
import com.jewelbox.player.data.net.FavoriteBody
import com.jewelbox.player.data.net.PlaylistDto
import com.jewelbox.player.data.net.PlaylistSummaryDto
import com.jewelbox.player.data.net.ReorderBody
import com.jewelbox.player.data.net.SmartPlaylistDto
import com.jewelbox.player.data.net.SmartPlaylistMetaDto
import kotlinx.coroutines.flow.first

/**
 * Thin data layer over the playlist, smart-playlist and favorite endpoints.
 * Same design as [AlbumRepository]: the server URL is re-read on each call so a
 * change in Settings takes effect immediately.
 */
class PlaylistRepository(private val prefs: ServerPrefs) {

    suspend fun currentServerUrl(): String = prefs.serverUrl.first()

    private suspend fun api() = ApiClient.create(currentServerUrl())

    // ── Playlists utilisateur ────────────────────────────────────────────────

    suspend fun playlists(): List<PlaylistSummaryDto> = api().playlists().data

    suspend fun playlist(id: Int): PlaylistDto = api().playlist(id)

    suspend fun createPlaylist(name: String): PlaylistDto =
        api().createPlaylist(CreatePlaylistBody(name))

    suspend fun renamePlaylist(id: Int, name: String): PlaylistDto =
        api().renamePlaylist(id, CreatePlaylistBody(name))

    suspend fun deletePlaylist(id: Int) = api().deletePlaylist(id)

    suspend fun addTrack(playlistId: Int, trackId: Int): PlaylistDto =
        api().addToPlaylist(playlistId, AddTracksBody(trackId = trackId))

    suspend fun addAlbum(playlistId: Int, albumId: Int): PlaylistDto =
        api().addToPlaylist(playlistId, AddTracksBody(albumId = albumId))

    suspend fun removeEntry(playlistId: Int, entryId: Int): PlaylistDto =
        api().removePlaylistEntry(playlistId, entryId)

    suspend fun reorder(playlistId: Int, entryIds: List<Int>): PlaylistDto =
        api().reorderPlaylist(playlistId, ReorderBody(entryIds))

    // ── Listes intelligentes ─────────────────────────────────────────────────

    suspend fun smartPlaylists(): List<SmartPlaylistMetaDto> = api().smartPlaylists().data

    suspend fun smartPlaylist(key: String): SmartPlaylistDto = api().smartPlaylist(key)

    /** Rotates the persistent dynamic mix after a track was played through. */
    suspend fun dynamicMixPlayed(trackId: Int): DynamicMixPlayedDto =
        api().dynamicMixPlayed(DynamicMixPlayedBody(trackId))

    /** Throws the current mix away and draws a brand-new one. */
    suspend fun refreshDynamicMix(): SmartPlaylistDto = api().dynamicMixRefresh()

    /** Manually drops a disliked track from the mix; the server refills the list. */
    suspend fun removeDynamicMixTrack(trackId: Int): DynamicMixPlayedDto =
        api().dynamicMixRemove(trackId)

    // ── Favoris ──────────────────────────────────────────────────────────────

    suspend fun setFavorite(trackId: Int, isFavorite: Boolean) =
        api().setFavorite(trackId, FavoriteBody(isFavorite))
}
