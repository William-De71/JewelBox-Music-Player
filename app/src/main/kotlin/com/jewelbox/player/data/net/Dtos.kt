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
