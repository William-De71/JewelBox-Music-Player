package com.jewelbox.player.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.jewelbox.player.R
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.resolveCover
import com.jewelbox.player.ui.playlists.DYNAMIC_MIX_KEY
import com.jewelbox.player.ui.playlists.SMART_SPECS
import com.jewelbox.player.ui.playlists.smartSpec
import kotlinx.coroutines.flow.first

/**
 * The browse tree Android Auto shows, and what tapping an item (or asking by
 * voice) plays. Four tabs: home (resume + suggestions), library (artists →
 * albums), playlists (smart lists + the user's) and downloads, which works
 * without the server. Queues are built by [PlayerConnection] so a queue started
 * from the car is counted, scrobbled and resumed exactly like one from the app.
 */
class AutoLibrary(private val context: Context) {

    // Owned albums with audio, as last fetched for the library tab: an artist's
    // page reuses it instead of downloading the whole collection again.
    private var albums: List<AlbumDto> = emptyList()

    fun root(): MediaItem = browsable(
        AutoMediaId.Root, context.getString(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        // Artists as rows, albums as a grid of covers.
        extras = contentStyle(playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
    )

    /** Children of [parentId]; a server error is reported as such, never thrown. */
    suspend fun children(parentId: String, page: Int, pageSize: Int): LibraryResult<ImmutableList<MediaItem>> {
        val id = AutoMediaId.parse(parentId) ?: return LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        val items = runCatching { load(id) }.getOrElse { return LibraryResult.ofError(SessionError.ERROR_IO) }
        return LibraryResult.ofItemList(pageOf(items, page, pageSize), null)
    }

    private suspend fun load(id: AutoMediaId): List<MediaItem> = when (id) {
        AutoMediaId.Root -> listOf(
            browsable(AutoMediaId.Home, context.getString(R.string.home_title)),
            browsable(AutoMediaId.Library, context.getString(R.string.library_title)),
            // Playlists have no cover of their own: rows read better than an empty grid.
            browsable(
                AutoMediaId.Playlists, context.getString(R.string.playlists_title),
                extras = contentStyle(playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
            ),
            browsable(AutoMediaId.Downloads, context.getString(R.string.offline_title)),
        )
        AutoMediaId.Home -> home()
        AutoMediaId.Library -> artists()
        is AutoMediaId.Artist -> {
            if (albums.isEmpty()) albums = fetchAlbums()
            albums.filter { it.artist.id == id.id }.sortedBy { it.year ?: Int.MAX_VALUE }
                .map { album(it, serverUrl()) }
        }
        AutoMediaId.Playlists -> playlists()
        AutoMediaId.Downloads -> downloads()
        else -> emptyList()
    }

    private suspend fun home(): List<MediaItem> {
        val serverUrl = serverUrl()
        val home = ServiceLocator.homeRepository.home()
        val recentTitle = context.getString(R.string.home_recent_header)
        val recent = home.recent.mapNotNull { entry ->
            when {
                entry.album != null -> album(entry.album, serverUrl, group = recentTitle)
                entry.playlist != null -> playable(
                    AutoMediaId.Playlist(entry.playlist.id), entry.playlist.name, null,
                    resolveCover(serverUrl, entry.playlist.coverUrl), MediaMetadata.MEDIA_TYPE_PLAYLIST, recentTitle,
                )
                entry.smart != null -> smart(entry.smart.key, recentTitle)
                else -> null
            }
        }
        val suggestionsTitle = context.getString(R.string.home_suggestions_header)
        return recent + home.suggestions.map { album(it, serverUrl, group = suggestionsTitle) }
    }

    private suspend fun artists(): List<MediaItem> {
        albums = fetchAlbums()
        // Already sorted by artist by the server: first appearance keeps that order.
        return albums.distinctBy { it.artist.id }.map {
            browsable(AutoMediaId.Artist(it.artist.id), it.artist.name, MediaMetadata.MEDIA_TYPE_ARTIST)
        }
    }

    private suspend fun fetchAlbums(): List<AlbumDto> =
        ServiceLocator.albumRepository.albums(page = 1, limit = ALBUMS_LIMIT).data.filter { it.hasAudio }

    private suspend fun playlists(): List<MediaItem> {
        val smartTitle = context.getString(R.string.smart_playlists_header)
        // Smart lists are fixed client-side; only the user's playlists need the server.
        val smart = SMART_SPECS.mapNotNull { smart(it.key, smartTitle) }
        val mineTitle = context.getString(R.string.my_playlists_header)
        val mine = ServiceLocator.playlistRepository.playlists().map {
            playable(AutoMediaId.Playlist(it.id), it.name, null, null, MediaMetadata.MEDIA_TYPE_PLAYLIST, mineTitle)
        }
        return smart + mine
    }

    private fun downloads(): List<MediaItem> {
        val catalog = ServiceLocator.offlineStore.catalog.value
        val albumsTitle = context.getString(R.string.offline_albums)
        val albums = catalog.albums.sortedBy { it.album.artist.name.lowercase() }.map {
            playable(
                AutoMediaId.DownloadedAlbum(it.scope, it.album.id), it.album.title, it.album.artist.name,
                resolveCover(it.serverUrl, it.album.coverUrl), MediaMetadata.MEDIA_TYPE_ALBUM, albumsTitle,
            )
        }
        val playlistsTitle = context.getString(R.string.offline_playlists)
        val playlists = catalog.playlists.sortedBy { it.playlist.name.lowercase() }.map {
            playable(
                AutoMediaId.DownloadedPlaylist(it.scope, it.playlist.id), it.playlist.name, null,
                resolveCover(it.serverUrl, it.playlist.tracks.firstNotNullOfOrNull { t -> t.coverUrl }),
                MediaMetadata.MEDIA_TYPE_PLAYLIST, playlistsTitle,
            )
        }
        return albums + playlists
    }

    /**
     * What Auto asked to play: a tapped item (only its media id comes back) or
     * a voice request ("play … on JewelBox"). Null when it isn't one of ours —
     * the session then treats the items as usual (the app's own controller).
     */
    suspend fun resolve(items: List<MediaItem>): QueueStart? {
        val item = items.singleOrNull() ?: return null
        if (item.localConfiguration != null) return null
        // A voice request carries no media id; "play music" may not even carry a query.
        val query = item.requestMetadata.searchQuery ?: if (item.mediaId.isEmpty()) "" else null
        if (query != null) return runCatching { search(query) }.getOrNull()
        val id = AutoMediaId.parse(item.mediaId) ?: return null
        return runCatching { queueFor(id) }.getOrNull()
    }

    private suspend fun queueFor(id: AutoMediaId): QueueStart? = when (id) {
        is AutoMediaId.Album -> {
            val album = ServiceLocator.albumRepository.album(id.id)
            album.tracks.firstOrNull { it.hasFile }?.let { PlayerConnection.prepareAlbum(serverUrl(), album, it.id) }
        }
        is AutoMediaId.Playlist -> {
            val playlist = ServiceLocator.playlistRepository.playlist(id.id)
            PlayerConnection.prepareQueue(
                serverUrl(), playlist.tracks,
                source = QueueSource.Playlist(playlist.id), sourceLabel = playlist.name,
            )
        }
        is AutoMediaId.Smart -> {
            val smart = ServiceLocator.playlistRepository.smartPlaylist(id.key)
            PlayerConnection.prepareQueue(
                serverUrl(), smart.tracks,
                dynamicMix = id.key == DYNAMIC_MIX_KEY, source = QueueSource.Smart(id.key),
            )
        }
        is AutoMediaId.DownloadedAlbum -> {
            val kept = ServiceLocator.offlineStore.catalog.value.album(id.scope, id.id)
            kept?.album?.tracks?.firstOrNull { it.hasFile }?.let {
                PlayerConnection.prepareAlbum(kept.serverUrl, kept.album, it.id, offlineScope = kept.scope)
            }
        }
        is AutoMediaId.DownloadedPlaylist -> {
            val kept = ServiceLocator.offlineStore.catalog.value.playlist(id.scope, id.id)
            kept?.let {
                PlayerConnection.prepareQueue(
                    it.serverUrl, it.playlist.tracks,
                    source = QueueSource.Playlist(it.playlist.id), sourceLabel = it.playlist.name,
                    offlineScope = it.scope,
                )
            }
        }
        else -> null
    }

    /**
     * Voice request: the best matching album, else the matching tracks. No
     * query at all ("play music on JewelBox") starts the dynamic mix.
     */
    private suspend fun search(query: String): QueueStart? {
        if (query.isBlank()) return queueFor(AutoMediaId.Smart(DYNAMIC_MIX_KEY))
        val results = ServiceLocator.albumRepository.search(query)
        results.albums.firstOrNull { it.hasAudio }?.let { return queueFor(AutoMediaId.Album(it.id)) }
        return PlayerConnection.prepareQueue(serverUrl(), results.tracks, sourceLabel = query)
    }

    private suspend fun serverUrl(): String = ServiceLocator.serverPrefs.serverUrl.first()

    private fun album(album: AlbumDto, serverUrl: String, group: String? = null): MediaItem = playable(
        AutoMediaId.Album(album.id), album.title, album.artist.name,
        resolveCover(serverUrl, album.coverUrl), MediaMetadata.MEDIA_TYPE_ALBUM, group,
    )

    private fun smart(key: String, group: String): MediaItem? {
        val spec = smartSpec(key) ?: return null
        return playable(AutoMediaId.Smart(key), context.getString(spec.label), null, null, MediaMetadata.MEDIA_TYPE_PLAYLIST, group)
    }

    /** Layout hint for the children of a browsable item (and of the whole tree, on the root). */
    private fun contentStyle(playable: Int) = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playable)
    }

    private fun browsable(
        id: AutoMediaId,
        title: String,
        type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        extras: Bundle? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id.value)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .setExtras(extras)
                .build(),
        )
        .build()

    private fun playable(
        id: AutoMediaId,
        title: String,
        subtitle: String?,
        cover: String?,
        type: Int,
        group: String?,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id.value)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(type)
                .apply { cover?.let { setArtworkUri(Uri.parse(it)) } }
                // Auto shows consecutive items with the same group title under one header.
                .setExtras(group?.let { Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) } })
                .build(),
        )
        .build()

    private companion object {
        /** Same as the library screen: the whole collection in one call. */
        const val ALBUMS_LIMIT = 10000
    }
}

/** Auto expects a [MediaSession.MediaItemsWithStartPosition] from the session callback. */
fun QueueStart.toSessionItems() = MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs)
