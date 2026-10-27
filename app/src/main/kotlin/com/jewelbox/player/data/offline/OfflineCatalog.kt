package com.jewelbox.player.data.offline

import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.PlaylistDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the user asked to keep for offline listening: albums and playlists,
 * each with everything needed to show and play it without the server.
 *
 * [scope] names the collection the item comes from ("<server_id>/<collection
 * name>"): track ids are only unique within one collection of one server,
 * so the downloaded audio is keyed by scope + track id ([cacheKey]), and an
 * album downloaded from another collection never answers for a track of the
 * active one. [serverUrl] is the address it was downloaded from: the player
 * needs a URI, the download cache answers it by key.
 */
@Serializable
data class OfflineAlbum(
    val scope: String,
    @SerialName("server_url") val serverUrl: String,
    val album: AlbumDto,
    @SerialName("added_at") val addedAt: Long = 0L,
)

@Serializable
data class OfflinePlaylist(
    val scope: String,
    @SerialName("server_url") val serverUrl: String,
    val playlist: PlaylistDto,
    @SerialName("added_at") val addedAt: Long = 0L,
)

@Serializable
data class OfflineCatalog(
    val albums: List<OfflineAlbum> = emptyList(),
    val playlists: List<OfflinePlaylist> = emptyList(),
) {
    fun album(scope: String?, id: Int): OfflineAlbum? =
        albums.firstOrNull { it.album.id == id && (scope == null || it.scope == scope) }

    fun playlist(scope: String?, id: Int): OfflinePlaylist? =
        playlists.firstOrNull { it.playlist.id == id && (scope == null || it.scope == scope) }

    fun withAlbum(item: OfflineAlbum) =
        copy(albums = albums.filterNot { it.scope == item.scope && it.album.id == item.album.id } + item)

    fun withPlaylist(item: OfflinePlaylist) =
        copy(playlists = playlists.filterNot { it.scope == item.scope && it.playlist.id == item.playlist.id } + item)

    fun withoutAlbum(scope: String, id: Int) =
        copy(albums = albums.filterNot { it.scope == scope && it.album.id == id })

    fun withoutPlaylist(scope: String, id: Int) =
        copy(playlists = playlists.filterNot { it.scope == scope && it.playlist.id == id })
}

/** The scope of a collection: server identity + collection name. */
fun scopeOf(serverId: String, collection: String?): String = "$serverId/${collection.orEmpty()}"

/** Key of a downloaded track: the download id and the player's cache key. */
fun cacheKey(scope: String, trackId: Int): String = "$scope#track:$trackId"

/** One track to have on the phone. */
data class OfflineTrack(val key: String, val serverUrl: String, val trackId: Int)

/** Every track the catalog needs, once each (a track may be in an album and a playlist). */
fun wantedTracks(catalog: OfflineCatalog): Map<String, OfflineTrack> {
    val out = LinkedHashMap<String, OfflineTrack>()
    for (item in catalog.albums) {
        for (track in item.album.tracks.filter { it.hasFile }) {
            val key = cacheKey(item.scope, track.id)
            out.getOrPut(key) { OfflineTrack(key, item.serverUrl, track.id) }
        }
    }
    for (item in catalog.playlists) {
        for (track in item.playlist.tracks.filter { it.hasFile }) {
            val key = cacheKey(item.scope, track.id)
            out.getOrPut(key) { OfflineTrack(key, item.serverUrl, track.id) }
        }
    }
    return out
}

/** Downloads to start and to drop so the phone holds exactly [wanted]. */
data class Reconciliation(val toAdd: List<OfflineTrack>, val toRemove: List<String>)

fun reconcile(wanted: Map<String, OfflineTrack>, present: Set<String>): Reconciliation =
    Reconciliation(
        toAdd = wanted.values.filter { it.key !in present },
        toRemove = present.filter { it !in wanted }.sorted(),
    )

/** Where a download stands, as the player library reports it. */
enum class TrackState { QUEUED, DOWNLOADING, DONE, FAILED }

data class TrackProgress(val state: TrackState, val percent: Float = 0f)

/** The download state of an album or a playlist, from its tracks. */
sealed interface ItemStatus {
    data object NotDownloaded : ItemStatus

    /** [fraction] 0–1 over all its tracks; [waiting] when nothing moves (no Wi-Fi…). */
    data class Downloading(val fraction: Float, val waiting: Boolean) : ItemStatus

    data object Downloaded : ItemStatus

    data class Failed(val failed: Int) : ItemStatus
}

fun itemStatus(kept: Boolean, keys: List<String>, progress: Map<String, TrackProgress>): ItemStatus {
    if (!kept) return ItemStatus.NotDownloaded
    if (keys.isEmpty()) return ItemStatus.Downloaded
    val states = keys.map { progress[it] }
    val failed = states.count { it?.state == TrackState.FAILED }
    if (failed > 0) return ItemStatus.Failed(failed)
    if (states.all { it?.state == TrackState.DONE }) return ItemStatus.Downloaded
    val fraction = states.sumOf {
        when (it?.state) {
            TrackState.DONE -> 1.0
            TrackState.DOWNLOADING -> (it.percent / 100f).coerceIn(0f, 1f).toDouble()
            else -> 0.0
        }
    } / keys.size
    val waiting = states.none { it?.state == TrackState.DOWNLOADING }
    return ItemStatus.Downloading(fraction.toFloat(), waiting)
}

/** The keys of the playable tracks of an album / a playlist of [scope]. */
fun albumKeys(scope: String, album: AlbumDto): List<String> =
    album.tracks.filter { it.hasFile }.map { cacheKey(scope, it.id) }

fun playlistKeys(scope: String, playlist: PlaylistDto): List<String> =
    playlist.tracks.filter { it.hasFile }.map { cacheKey(scope, it.id) }.distinct()
