package com.jewelbox.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.ApiClient
import com.jewelbox.player.data.net.PlaylistDto
import com.jewelbox.player.data.offline.ItemStatus
import com.jewelbox.player.data.offline.OfflineAlbum
import com.jewelbox.player.data.offline.OfflinePlaylist
import com.jewelbox.player.data.offline.PendingPlay
import com.jewelbox.player.data.offline.TrackProgress
import com.jewelbox.player.data.offline.TrackState
import com.jewelbox.player.data.offline.albumKeys
import com.jewelbox.player.data.offline.enqueuePlay
import com.jewelbox.player.data.offline.itemStatus
import com.jewelbox.player.data.offline.playedAtIso
import com.jewelbox.player.data.offline.playlistKeys
import com.jewelbox.player.data.offline.reconcile
import com.jewelbox.player.data.offline.scopeOf
import com.jewelbox.player.data.offline.scrobbleExpired
import com.jewelbox.player.data.offline.wantedTracks
import com.jewelbox.player.data.resolveCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import retrofit2.HttpException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Offline listening: albums and playlists downloaded on the phone, and the
 * plays made without the server, sent on its return.
 *
 * The audio goes through Media3's download library: one [DownloadRequest] per
 * track, keyed by [com.jewelbox.player.data.offline.cacheKey] (collection +
 * track id) into a [SimpleCache] the player reads first (see
 * [PlaybackService]: a downloaded track plays from the phone, online or not;
 * streaming never writes into it). The catalog (what to keep, with the album
 * and playlist details) lives in [com.jewelbox.player.data.offline.OfflineStore];
 * the downloads always follow it ([sync]).
 */
@OptIn(UnstableApi::class)
object Offline {

    const val NOTIFICATION_CHANNEL = "downloads"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var appContext: Context
    lateinit var cache: SimpleCache
        private set
    lateinit var manager: DownloadManager
        private set
    lateinit var notifications: DownloadNotificationHelper
        private set

    /** The collection the server currently serves (null until known: offline). */
    private val _currentScope = MutableStateFlow<String?>(null)
    val currentScope: StateFlow<String?> = _currentScope.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, TrackProgress>>(emptyMap())
    val progress: StateFlow<Map<String, TrackProgress>> = _progress.asStateFlow()

    /** Bytes of audio on the phone. */
    private val _bytes = MutableStateFlow(0L)
    val bytes: StateFlow<Long> = _bytes.asStateFlow()

    private val syncMutex = Mutex()

    fun init(context: Context) {
        if (::manager.isInitialized) return
        appContext = context.applicationContext
        val database = StandaloneDatabaseProvider(appContext)
        cache = SimpleCache(File(appContext.filesDir, "offline/audio"), NoOpCacheEvictor(), database)
        manager = DownloadManager(
            appContext, database, cache, DefaultHttpDataSource.Factory(), Executors.newFixedThreadPool(2),
        ).apply { maxParallelDownloads = 2 }
        notifications = DownloadNotificationHelper(appContext, NOTIFICATION_CHANNEL)
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                refreshProgress()
            }

            override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                refreshProgress()
            }

            override fun onInitialized(manager: DownloadManager) {
                refreshProgress()
                scope.launch { sync() }
            }
        })
        // Downloads on mobile data only when allowed (the server is on the LAN:
        // in practice Wi-Fi, or a VPN over mobile data).
        scope.launch {
            ServiceLocator.serverPrefs.downloadOnMobile.collect { allowed ->
                manager.requirements = Requirements(
                    if (allowed) Requirements.NETWORK else Requirements.NETWORK_UNMETERED,
                )
            }
        }
        // Percentages are not pushed by the manager: poll while something moves.
        scope.launch {
            while (isActive) {
                if (manager.currentDownloads.any { it.state == Download.STATE_DOWNLOADING }) refreshProgress()
                delay(1000)
            }
        }
        // On every server change and every minute: learn the collection, send
        // the plays kept while offline, follow the kept playlists.
        scope.launch {
            ServiceLocator.serverPrefs.serverUrl.distinctUntilChanged().collectLatest {
                while (isActive) {
                    refreshOnline()
                    delay(60_000)
                }
            }
        }
    }

    /** The player's data source: downloaded tracks from the phone, the rest from the server. */
    fun playerDataSourceFactory(): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            // Streaming must not fill the download cache (NoOp evictor: it would grow forever).
            .setCacheWriteDataSinkFactory(null)

    private fun refreshProgress() {
        val map = HashMap<String, TrackProgress>()
        var bytes = 0L
        scope.launch(Dispatchers.IO) {
            runCatching {
                manager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        val download = cursor.download
                        map[download.request.id] = progressOf(download)
                        if (download.state == Download.STATE_COMPLETED) bytes += download.bytesDownloaded
                    }
                }
            }
            withContext(Dispatchers.Main) {
                for (download in manager.currentDownloads) map[download.request.id] = progressOf(download)
                _progress.value = map
                _bytes.value = bytes
            }
        }
    }

    private fun progressOf(download: Download) = when (download.state) {
        Download.STATE_COMPLETED -> TrackProgress(TrackState.DONE, 100f)
        Download.STATE_FAILED -> TrackProgress(TrackState.FAILED)
        Download.STATE_DOWNLOADING -> TrackProgress(TrackState.DOWNLOADING, download.percentDownloaded.coerceAtLeast(0f))
        else -> TrackProgress(TrackState.QUEUED)
    }

    // ── Catalog ──────────────────────────────────────────────────────────────

    /** The collection of the configured server, asked to it (null: unreachable). */
    suspend fun refreshScope(): String? {
        val url = ServiceLocator.serverPrefs.serverUrl.first().ifBlank { return null }
        val info = runCatching { ApiClient.create(url).serverInfo() }.getOrNull() ?: return null
        if (info.serverId.isBlank()) return null
        return scopeOf(info.serverId, info.collection).also { _currentScope.value = it }
    }

    private suspend fun requireScope(): Pair<String, String> {
        val url = ServiceLocator.serverPrefs.serverUrl.first()
        val scope = refreshScope() ?: throw IllegalStateException("server unreachable")
        return scope to url
    }

    suspend fun keepAlbum(album: AlbumDto) {
        val (scope, url) = requireScope()
        ServiceLocator.offlineStore.updateCatalog {
            it.withAlbum(OfflineAlbum(scope, url, album, System.currentTimeMillis()))
        }
        saveCover(url, album.coverUrl)
        sync()
    }

    suspend fun keepPlaylist(playlist: PlaylistDto) {
        val (scope, url) = requireScope()
        ServiceLocator.offlineStore.updateCatalog {
            it.withPlaylist(OfflinePlaylist(scope, url, playlist, System.currentTimeMillis()))
        }
        playlist.tracks.mapNotNull { it.coverUrl }.distinct().forEach { saveCover(url, it) }
        sync()
    }

    suspend fun removeAlbum(scope: String, id: Int) {
        ServiceLocator.offlineStore.updateCatalog { it.withoutAlbum(scope, id) }
        sync()
    }

    suspend fun removePlaylist(scope: String, id: Int) {
        ServiceLocator.offlineStore.updateCatalog { it.withoutPlaylist(scope, id) }
        sync()
    }

    /** A fresh album / playlist seen online: a kept one follows its changes. */
    suspend fun onAlbumLoaded(album: AlbumDto) {
        val scope = _currentScope.value ?: return
        val kept = ServiceLocator.offlineStore.catalog.value.album(scope, album.id) ?: return
        if (kept.album == album) return
        ServiceLocator.offlineStore.updateCatalog { it.withAlbum(kept.copy(album = album)) }
        sync()
    }

    suspend fun onPlaylistLoaded(playlist: PlaylistDto) {
        val scope = _currentScope.value ?: return
        val kept = ServiceLocator.offlineStore.catalog.value.playlist(scope, playlist.id) ?: return
        if (kept.playlist == playlist) return
        ServiceLocator.offlineStore.updateCatalog { it.withPlaylist(kept.copy(playlist = playlist)) }
        playlist.tracks.mapNotNull { it.coverUrl }.distinct().forEach { saveCover(kept.serverUrl, it) }
        sync()
    }

    fun albumStatus(scope: String?, album: AlbumDto?): ItemStatus {
        if (scope == null || album == null) return ItemStatus.NotDownloaded
        val kept = ServiceLocator.offlineStore.catalog.value.album(scope, album.id) != null
        return itemStatus(kept, albumKeys(scope, album), _progress.value)
    }

    fun playlistStatus(scope: String?, playlist: PlaylistDto?): ItemStatus {
        if (scope == null || playlist == null) return ItemStatus.NotDownloaded
        val kept = ServiceLocator.offlineStore.catalog.value.playlist(scope, playlist.id) != null
        return itemStatus(kept, playlistKeys(scope, playlist), _progress.value)
    }

    /** Starts what the catalog needs, drops what it no longer needs, retries failures. */
    suspend fun sync() = syncMutex.withLock {
        if (!manager.isInitialized) return@withLock
        val wanted = wantedTracks(ServiceLocator.offlineStore.catalog.value)
        val present = HashSet<String>()
        val failed = HashSet<String>()
        withContext(Dispatchers.IO) {
            runCatching {
                manager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        present += cursor.download.request.id
                        if (cursor.download.state == Download.STATE_FAILED) failed += cursor.download.request.id
                    }
                }
            }
        }
        val plan = reconcile(wanted, present - failed)
        for (key in plan.toRemove) {
            DownloadService.sendRemoveDownload(appContext, JewelBoxDownloadService::class.java, key, false)
        }
        for (track in plan.toAdd) {
            val uri = track.serverUrl.trimEnd('/') + "/api/player/tracks/${track.trackId}/stream"
            val request = DownloadRequest.Builder(track.key, android.net.Uri.parse(uri))
                .setCustomCacheKey(track.key)
                .build()
            DownloadService.sendAddDownload(appContext, JewelBoxDownloadService::class.java, request, false)
        }
    }

    // ── Covers ───────────────────────────────────────────────────────────────

    private fun coverFile(coverUrl: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(coverUrl.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(ServiceLocator.offlineStore.coversDir, name)
    }

    /** The cover kept on the phone, as a file URI, or null. */
    fun localCover(coverUrl: String?): String? {
        if (coverUrl.isNullOrBlank() || !::appContext.isInitialized) return null
        val file = coverFile(coverUrl)
        return if (file.isFile) android.net.Uri.fromFile(file).toString() else null
    }

    private suspend fun saveCover(serverUrl: String, coverUrl: String?) = withContext(Dispatchers.IO) {
        val url = resolveCover(serverUrl, coverUrl) ?: return@withContext
        val file = coverFile(coverUrl!!)
        if (file.isFile) return@withContext
        runCatching {
            ApiClient.httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body ?: return@use
                if (!response.isSuccessful) return@use
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, ".${file.name}.tmp")
                tmp.outputStream().use { body.byteStream().copyTo(it) }
                tmp.renameTo(file)
            }
        }
    }

    // ── Back online ──────────────────────────────────────────────────────────

    private suspend fun refreshOnline() {
        val scope = refreshScope() ?: return
        flushPendingPlays()
        // Kept playlists follow the server (tracks added, removed, reordered).
        val kept = ServiceLocator.offlineStore.catalog.value.playlists.filter { it.scope == scope }
        for (item in kept) {
            val fresh = runCatching { ServiceLocator.playlistRepository.playlist(item.playlist.id) }
            val error = fresh.exceptionOrNull()
            when {
                fresh.isSuccess -> onPlaylistLoaded(fresh.getOrThrow())
                // Deleted on the server: nothing left to follow, but it stays
                // playable as it was until the user removes it.
                error is HttpException && error.code() == 404 -> Unit
                else -> return
            }
        }
    }

    /**
     * A play the server must hear about (counter + Last.fm): sent now, or kept
     * until it answers. Plays already waiting go first, so the order holds.
     */
    fun reportPlay(trackId: Int, startedAt: Long) {
        scope.launch {
            val play = PendingPlay(trackId, startedAt, System.currentTimeMillis())
            val store = ServiceLocator.offlineStore
            val left = if (store.pendingPlays.value.isEmpty()) send(play) else play
            if (left != null) {
                store.updatePendingPlays { enqueuePlay(it, left) }
                flushPendingPlays()
            }
        }
    }

    private val flushMutex = Mutex()

    /** Oldest first; stops at the first one the server could not take. */
    suspend fun flushPendingPlays() = flushMutex.withLock {
        val store = ServiceLocator.offlineStore
        while (true) {
            val play = store.pendingPlays.value.firstOrNull() ?: return@withLock
            val left = send(play)
            store.updatePendingPlays { queue ->
                if (queue.firstOrNull() != play) queue
                else if (left == null) queue.drop(1)
                else listOf(left) + queue.drop(1)
            }
            if (left != null) return@withLock
        }
    }

    /**
     * Sends what is left of a play. Null once the server has it all (or never
     * will: an unknown track, a scrobble too old for Last.fm); else what is
     * still to send (the counter took it, the scrobble did not get through).
     */
    private suspend fun send(play: PendingPlay): PendingPlay? {
        val repo = ServiceLocator.albumRepository
        var current = play
        if (!current.counted) {
            val error = runCatching { repo.markPlayed(play.trackId, play.playedAtIso()) }.exceptionOrNull()
            if (error is HttpException && error.code() == 404) return null
            if (error != null) return current
            current = current.copy(counted = true)
        }
        if (current.scrobbleExpired(System.currentTimeMillis() / 1000)) return null
        return if (runCatching { repo.scrobble(play.trackId, play.startedAt) }.isSuccess) null else current
    }
}
