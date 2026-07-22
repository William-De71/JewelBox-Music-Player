package com.jewelbox.player.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs mirror the JSON shapes returned by the JewelBox Fastify server.
 * Field names match the server exactly (see server/src/db/queries.js#mapAlbum).
 * Every optional field is nullable with a default so a partial/extra payload
 * never crashes deserialization.
 */

@Serializable
data class HealthDto(
    val status: String = "",
)

/**
 * GET /api/server-info — the server's identity card (server >= 1.12).
 * Fetched right after an mDNS resolution to confirm the service really is a
 * JewelBox (app == "jewelbox") and not something else squatting the port.
 * server_id is a UUID minted on the server's first boot: it survives DHCP
 * address changes, which is what lets the app recognize "its" server again.
 */
@Serializable
data class ServerInfoDto(
    val app: String = "",
    val name: String = "",
    val version: String = "",
    @SerialName("server_id") val serverId: String = "",
    val api: String = "",
    val collection: String? = null,
)

/** Body of POST /api/lastfm/nowplaying — the server enriches from its DB and calls Last.fm. */
@Serializable
data class NowPlayingBody(
    @SerialName("track_id") val trackId: Int,
)

/** Body of POST /api/lastfm/scrobble — started_at is the epoch second playback began. */
@Serializable
data class ScrobbleBody(
    @SerialName("track_id") val trackId: Int,
    @SerialName("started_at") val startedAt: Long,
)

@Serializable
data class ArtistDto(
    val id: Int,
    val name: String,
)

@Serializable
data class LabelDto(
    val id: Int,
    val name: String,
)

@Serializable
data class TrackDto(
    val id: Int,
    val position: Int = 0,
    val title: String,
    val duration: String? = null,
    @SerialName("has_file") val hasFile: Boolean = false,
    @SerialName("play_count") val playCount: Int = 0,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
)

@Serializable
data class AlbumDto(
    val id: Int,
    val title: String,
    val year: Int? = null,
    val genre: String? = null,
    val rating: Int? = null,
    @SerialName("total_duration") val totalDuration: String? = null,
    val ean: String? = null,
    val notes: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("has_audio") val hasAudio: Boolean = false,
    val artist: ArtistDto,
    val label: LabelDto? = null,
    // Present only on the album-detail response (GET /api/albums/:id); empty in list responses.
    val tracks: List<TrackDto> = emptyList(),
)

/**
 * Track as shaped by the server's queue endpoints (playlists and smart
 * playlists, see server/src/db/queries.js#mapQueueTrack): carries its album and
 * artist so a heterogeneous list is playable as-is. entry_id is present only in
 * user-playlist responses, where it identifies the playlist row (a track can
 * appear twice in the same playlist).
 */
@Serializable
data class QueueTrackDto(
    val id: Int,
    @SerialName("entry_id") val entryId: Int? = null,
    val position: Int = 0,
    val title: String,
    val duration: String? = null,
    @SerialName("has_file") val hasFile: Boolean = false,
    @SerialName("play_count") val playCount: Int = 0,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("album_id") val albumId: Int = 0,
    @SerialName("album_title") val albumTitle: String = "",
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("cover_url") val coverUrl: String? = null,
)

/** Row of GET /api/playlists — counts and duration are aggregated server-side. */
@Serializable
data class PlaylistSummaryDto(
    val id: Int,
    val name: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("total_duration_seconds") val totalDurationSeconds: Int = 0,
    // Borrowed from the first track's album; only the home feed sets it,
    // GET /api/playlists simply omits the field.
    @SerialName("cover_url") val coverUrl: String? = null,
)

@Serializable
data class PlaylistsResponse(
    val data: List<PlaylistSummaryDto> = emptyList(),
)

/** Full playlist (GET /api/playlists/:id and every mutation response). */
@Serializable
data class PlaylistDto(
    val id: Int,
    val name: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val tracks: List<QueueTrackDto> = emptyList(),
    // Only in the POST /playlists/:id/tracks response: how many were added.
    val added: Int = 0,
)

@Serializable
data class SmartPlaylistMetaDto(
    val key: String,
    @SerialName("track_count") val trackCount: Int = 0,
)

@Serializable
data class SmartPlaylistsResponse(
    val data: List<SmartPlaylistMetaDto> = emptyList(),
)

@Serializable
data class SmartPlaylistDto(
    val key: String = "",
    val tracks: List<QueueTrackDto> = emptyList(),
)

@Serializable
data class CreatePlaylistBody(
    val name: String,
)

/** Body of POST /api/playlists/:id/tracks — exactly one of the two ids is set. */
@Serializable
data class AddTracksBody(
    @SerialName("track_id") val trackId: Int? = null,
    @SerialName("album_id") val albumId: Int? = null,
)

/** Body of PUT /api/playlists/:id/tracks — the full entry order, nothing partial. */
@Serializable
data class ReorderBody(
    @SerialName("entry_ids") val entryIds: List<Int>,
)

@Serializable
data class FavoriteBody(
    @SerialName("is_favorite") val isFavorite: Boolean,
)

@Serializable
data class DynamicMixPlayedBody(
    @SerialName("track_id") val trackId: Int,
)

/** Response of POST /api/smart-playlists/dynamic_mix/played: the refilled list. */
@Serializable
data class DynamicMixPlayedDto(
    val removed: Boolean = false,
    val tracks: List<QueueTrackDto> = emptyList(),
)

/**
 * Response of GET /api/player/search (server >= 1.7): both sections in one call,
 * capped server-side (30 albums / 100 tracks) instead of paginated.
 */
@Serializable
data class SearchResultsDto(
    val albums: List<AlbumDto> = emptyList(),
    val tracks: List<QueueTrackDto> = emptyList(),
)

/**
 * Body of POST /api/player/history. item_type is "album", "playlist" or "smart".
 * Album/playlist carry item_id; a smart playlist has no numeric id and carries
 * item_key (its stable text key). Only the relevant field is serialized.
 */
@Serializable
data class PlayHistoryBody(
    @SerialName("item_type") val itemType: String,
    @SerialName("item_id") val itemId: Int? = null,
    @SerialName("item_key") val itemKey: String? = null,
)

/** Smart playlist summary in the home feed: label and icon are resolved client-side from the key. */
@Serializable
data class SmartSummaryDto(
    val key: String = "",
    @SerialName("track_count") val trackCount: Int = 0,
)

/** Entry of the home feed's recent section: exactly one of album/playlist/smart is set. */
@Serializable
data class HomeRecentItemDto(
    @SerialName("item_type") val itemType: String = "",
    @SerialName("played_at") val playedAt: String? = null,
    val album: AlbumDto? = null,
    val playlist: PlaylistSummaryDto? = null,
    val smart: SmartSummaryDto? = null,
)

/** Response of GET /api/player/home (server >= 1.9). */
@Serializable
data class HomeDto(
    val recent: List<HomeRecentItemDto> = emptyList(),
    val suggestions: List<AlbumDto> = emptyList(),
)

@Serializable
data class Pagination(
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 24,
    val totalPages: Int = 0,
)

@Serializable
data class AlbumsPage(
    val data: List<AlbumDto> = emptyList(),
    val pagination: Pagination = Pagination(),
)
