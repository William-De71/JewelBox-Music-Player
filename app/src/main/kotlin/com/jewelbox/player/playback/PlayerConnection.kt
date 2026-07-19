package com.jewelbox.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.SavedQueue
import com.jewelbox.player.data.SavedTrack
import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.QueueTrackDto
import com.jewelbox.player.data.net.TrackDto
import com.jewelbox.player.data.resolveCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Repeat cycle exposed to the UI without leaking Media3 constants. */
enum class RepeatMode { OFF, ALL, ONE }

/** Where the current queue was started from, so lists can flag their playing row. */
sealed interface QueueSource {
    data class Album(val albumId: Int) : QueueSource
    data class Playlist(val playlistId: Int) : QueueSource
    data class Smart(val key: String) : QueueSource
}

/** What the UI needs to render playback state (mini-player, current-track highlight). */
data class PlaybackUiState(
    val hasItem: Boolean = false,
    val isPlaying: Boolean = false,
    val currentTrackId: Int? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val isFavorite: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
)

/** Progress of the current item, refreshed every second (separate flow: only the
 *  now-playing screen needs it, the mini-player doesn't re-render on every tick). */
data class PlaybackPosition(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * App-scoped bridge to the PlaybackService's MediaSession. Exposes playback state
 * as StateFlows for Compose, the actions the UI needs, and drives play counting +
 * Last.fm scrobbling with the same rules as the PWA (client/src/components/
 * PlayerContext.jsx): track >= 30s, played half of it or 4 minutes, seeks ignored.
 * The Last.fm session itself lives server-side — shared with the PWA.
 */
object PlayerConnection {

    /** MediaMetadata extras key carrying the track's favorite flag through Media3. */
    private const val EXTRA_IS_FAVORITE = "jewelbox.is_favorite"

    /** Ticker runs every second; checkpoint the resume position every 5th one. */
    private const val POSITION_SAVE_INTERVAL_TICKS = 5

    private var ticksSinceQueueSave = 0

    private var controller: MediaController? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // True while the queue is the server's persistent dynamic mix: each finished
    // track is reported so the server rotates the list, and the queue is topped
    // up with the tracks it sends back (same behaviour as the PWA PlayerContext).
    private var dynamicMix = false
    private var queueServerUrl: String? = null

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _position = MutableStateFlow(PlaybackPosition())
    val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

    // Origin of the current queue; null until something is played. Survives
    // pause/stop on purpose: the list highlight follows the loaded queue.
    private val _queueSource = MutableStateFlow<QueueSource?>(null)
    val queueSource: StateFlow<QueueSource?> = _queueSource.asStateFlow()

    // Scrobble rules live in ScrobbleTracker (pure, unit-tested); this object only
    // feeds it player positions and fires the network calls it requests.
    private val scrobbleTracker = ScrobbleTracker()

    fun init(context: Context) {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = syncState(player)
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // AUTO means the previous item played through to its end —
                    // the trigger for the dynamic mix rotation (seeks/skips don't count).
                    val endedId = lastTrackId
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && endedId != null) {
                        onTrackEnded(endedId, queueExhausted = false)
                    }
                    onTrackStarted(mediaItem)
                    saveQueue()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    // The very last item of the queue finished (no AUTO transition then).
                    if (playbackState == Player.STATE_ENDED) {
                        lastTrackId?.let { onTrackEnded(it, queueExhausted = true) }
                    }
                }

                // Pausing is the usual prelude to leaving the app: checkpoint
                // right away rather than waiting for the next tick.
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) saveQueue()
                }
            })
            syncState(c)
            restoreSavedQueue()
            startTicker()
        }, MoreExecutors.directExecutor())
    }

    private fun syncState(player: Player) {
        val item = player.currentMediaItem
        _state.value = PlaybackUiState(
            hasItem = item != null,
            isPlaying = player.isPlaying,
            currentTrackId = item?.mediaId?.toIntOrNull(),
            title = item?.mediaMetadata?.title?.toString(),
            artist = item?.mediaMetadata?.artist?.toString(),
            album = item?.mediaMetadata?.albumTitle?.toString(),
            artworkUrl = item?.mediaMetadata?.artworkUri?.toString(),
            isFavorite = item?.mediaMetadata?.extras?.getBoolean(EXTRA_IS_FAVORITE) ?: false,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
        )
    }

    // Id of the item currently loaded in the player, so a transition can name
    // the track that just ended (the player has already moved on at that point).
    private var lastTrackId: Int? = null

    // Display data of every track queued this session, keyed by track id: what
    // MediaItem can't give back when serializing the queue (raw cover URL).
    private val savedTracks = mutableMapOf<Int, SavedTrack>()

    // True while restoring: the queue must not be saved back over itself, and
    // the restored source must not be re-reported as a fresh play.
    private var restoring = false

    /**
     * Reloads the queue the user left behind, paused at the exact position.
     * Nothing is started: the user presses play. Called once the controller is
     * connected and only when the player is genuinely empty, so a queue started
     * before the service finished binding always wins.
     */
    private fun restoreSavedQueue() {
        scope.launch {
            val saved = ServiceLocator.playbackStateStore.load() ?: return@launch
            val c = controller ?: return@launch
            if (c.mediaItemCount > 0 || saved.tracks.isEmpty()) return@launch

            restoring = true
            try {
                saved.tracks.forEach { savedTracks[it.id] = it }
                dynamicMix = saved.dynamicMix
                queueServerUrl = saved.serverUrl
                _queueSource.value = when (saved.sourceType) {
                    "album" -> saved.sourceId?.toIntOrNull()?.let(QueueSource::Album)
                    "playlist" -> saved.sourceId?.toIntOrNull()?.let(QueueSource::Playlist)
                    "smart" -> saved.sourceId?.let(QueueSource::Smart)
                    else -> null
                }
                val items = saved.tracks.map { mediaItem(saved.serverUrl, it) }
                c.setMediaItems(items, saved.index.coerceIn(0, items.lastIndex), saved.positionMs)
                // prepare() without play(): buffered and ready, silent until asked.
                c.prepare()
            } finally {
                restoring = false
            }
        }
    }

    /** Snapshots the queue and where we are in it, for the next app start. */
    private fun saveQueue() {
        if (restoring) return
        val c = controller ?: return
        val serverUrl = queueServerUrl ?: return
        val tracks = (0 until c.mediaItemCount)
            .mapNotNull { c.getMediaItemAt(it).mediaId.toIntOrNull() }
            .mapNotNull { savedTracks[it] }
        val source = _queueSource.value
        val snapshot = SavedQueue(
            serverUrl = serverUrl,
            tracks = tracks,
            index = c.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = c.currentPosition.coerceAtLeast(0L),
            sourceType = when (source) {
                is QueueSource.Album -> "album"
                is QueueSource.Playlist -> "playlist"
                is QueueSource.Smart -> "smart"
                null -> null
            },
            sourceId = when (source) {
                is QueueSource.Album -> source.albumId.toString()
                is QueueSource.Playlist -> source.playlistId.toString()
                is QueueSource.Smart -> source.key
                null -> null
            },
            dynamicMix = dynamicMix,
        )
        scope.launch {
            if (tracks.isEmpty()) {
                ServiceLocator.playbackStateStore.clear()
            } else {
                ServiceLocator.playbackStateStore.save(snapshot)
            }
        }
    }

    /** New track (or replay): reset scrobble bookkeeping and tell Last.fm "now playing". */
    private fun onTrackStarted(item: MediaItem?) {
        val trackId = item?.mediaId?.toIntOrNull()
        lastTrackId = trackId
        scrobbleTracker.onTrackStarted(trackId)
        if (trackId != null) {
            scope.launch { runCatching { ServiceLocator.albumRepository.nowPlaying(trackId) } }
        }
    }

    /**
     * A track played through to its end. In dynamic mix mode the server is told
     * (it removes the track and refills the bottom of the list) and the local
     * queue is brought up to date with what it sends back; [queueExhausted]
     * means nothing is left to play, so playback restarts on the fresh list —
     * mirroring the PWA (client/src/components/PlayerContext.jsx#consumeDynamicMix).
     */
    private fun onTrackEnded(trackId: Int, queueExhausted: Boolean) {
        if (!dynamicMix) return
        scope.launch {
            val fresh = runCatching { ServiceLocator.playlistRepository.dynamicMixPlayed(trackId) }
                .getOrNull() ?: return@launch
            // Another queue may have taken over while the request was in flight.
            if (dynamicMix) syncDynamicMixQueue(fresh.tracks, restart = queueExhausted)
        }
    }

    /**
     * The user manually removed a disliked track from the dynamic mix. Mirrors
     * the fresh server list into the queue; unlike the played-through rotation,
     * the removed track is yanked even while it is playing — Media3 then moves
     * on to the next item by itself.
     */
    fun onDynamicMixTrackRemoved(serverTracks: List<QueueTrackDto>) {
        if (!dynamicMix) return
        syncDynamicMixQueue(serverTracks, restart = false, yankCurrent = true)
    }

    private fun syncDynamicMixQueue(
        serverTracks: List<QueueTrackDto>,
        restart: Boolean,
        yankCurrent: Boolean = false,
    ) {
        val c = controller ?: return
        val serverUrl = queueServerUrl ?: return
        if (restart) {
            val items = serverTracks.filter { it.hasFile }.map { mediaItem(serverUrl, it) }
            if (items.isEmpty()) return
            c.setMediaItems(items, 0, 0L)
            c.prepare()
            c.play()
            return
        }
        val currentIds = (0 until c.mediaItemCount)
            .mapNotNull { c.getMediaItemAt(it).mediaId.toIntOrNull() }
        val plan = DynamicMixSync.plan(currentIds, serverTracks)
        // Append before removing, so yanking the playing item always leaves it
        // a next one to fall through to.
        c.addMediaItems(plan.append.filter { it.hasFile }.map { mediaItem(serverUrl, it) })
        for (id in plan.removeIds) {
            val index = (0 until c.mediaItemCount)
                .firstOrNull { c.getMediaItemAt(it).mediaId == id.toString() } ?: continue
            // The rotation never yanks the item being listened to, even if the
            // server dropped it; a manual removal does.
            if (index == c.currentMediaItemIndex && !yankCurrent) continue
            c.removeMediaItem(index)
        }
    }

    /**
     * One heartbeat per second while the controller lives: refreshes the position
     * flow and accumulates listened time. A paused player yields dt == 0 and a
     * seek yields |dt| >= 2s — neither counts as listening, mirroring the PWA.
     */
    private fun startTicker() {
        scope.launch {
            while (isActive) {
                controller?.let(::tick)
                delay(1000)
            }
        }
    }

    private fun tick(c: MediaController) {
        val durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val positionMs = c.currentPosition
        _position.value = PlaybackPosition(positionMs, durationMs)

        // Checkpoint the resume position while playing, rarely enough not to
        // hammer DataStore: losing at most 5s of progress is imperceptible.
        if (c.isPlaying && ++ticksSinceQueueSave >= POSITION_SAVE_INTERVAL_TICKS) {
            ticksSinceQueueSave = 0
            saveQueue()
        }

        val due = scrobbleTracker.onTick(positionMs / 1000.0, durationMs / 1000.0) ?: return
        scope.launch {
            // Local play counting first, independent of Last.fm availability.
            runCatching { ServiceLocator.albumRepository.markPlayed(due.trackId) }
            runCatching { ServiceLocator.albumRepository.scrobble(due.trackId, due.startedAt) }
        }
    }

    /**
     * Queues every playable track of [album] (those with an audio file) and starts
     * playback at [startTrackId].
     */
    fun playAlbum(serverUrl: String, album: AlbumDto, startTrackId: Int) {
        val c = controller ?: return
        val playable = album.tracks.filter { it.hasFile }
        if (playable.isEmpty()) return
        dynamicMix = false
        queueServerUrl = serverUrl
        _queueSource.value = QueueSource.Album(album.id)
        reportPlayStarted(QueueSource.Album(album.id))
        val startIndex = playable.indexOfFirst { it.id == startTrackId }.coerceAtLeast(0)
        val items = playable.map { track -> mediaItem(serverUrl, album, track) }
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
        saveQueue()
    }

    /**
     * Queues queue-shaped tracks (playlists and smart playlists) — only those
     * with an audio file — and starts playback at [startIndex] (an index within
     * the playable tracks). [dynamicMix] turns on the server-backed rotation of
     * the persistent mix.
     */
    fun playQueue(
        serverUrl: String,
        tracks: List<QueueTrackDto>,
        startIndex: Int = 0,
        dynamicMix: Boolean = false,
        source: QueueSource? = null,
    ) {
        val c = controller ?: return
        val playable = tracks.filter { it.hasFile }
        if (playable.isEmpty()) return
        this.dynamicMix = dynamicMix
        queueServerUrl = serverUrl
        _queueSource.value = source
        reportPlayStarted(source)
        val items = playable.map { mediaItem(serverUrl, it) }
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        c.prepare()
        c.play()
        saveQueue()
    }

    // Feeds the home screen's "recently played" section. Fire-and-forget: an
    // old server (404) or an unreachable one must never disturb playback.
    // Smart queues are deliberately not history items.
    private fun reportPlayStarted(source: QueueSource?) {
        val (type, id) = when (source) {
            is QueueSource.Album -> "album" to source.albumId
            is QueueSource.Playlist -> "playlist" to source.playlistId
            else -> return
        }
        scope.launch { runCatching { ServiceLocator.homeRepository.reportPlay(type, id) } }
    }

    /** Optimistic favorite flip of the current track, persisted server-side. */
    fun toggleFavorite() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val trackId = item.mediaId.toIntOrNull() ?: return
        val next = !(item.mediaMetadata.extras?.getBoolean(EXTRA_IS_FAVORITE) ?: false)
        c.replaceMediaItem(c.currentMediaItemIndex, withFavorite(item, next))
        scope.launch {
            runCatching { ServiceLocator.playlistRepository.setFavorite(trackId, next) }
                .onFailure {
                    // Server refused: put the flag back as it was, wherever the item is now.
                    val cc = controller ?: return@launch
                    val idx = (0 until cc.mediaItemCount)
                        .firstOrNull { cc.getMediaItemAt(it).mediaId == item.mediaId }
                        ?: return@launch
                    cc.replaceMediaItem(idx, withFavorite(cc.getMediaItemAt(idx), !next))
                }
        }
    }

    private fun withFavorite(item: MediaItem, favorite: Boolean): MediaItem {
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY)
            .apply { putBoolean(EXTRA_IS_FAVORITE, favorite) }
        return item.buildUpon()
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
    }

    fun previous() {
        // Standard behaviour: restart the track, or go back if near the beginning.
        controller?.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** OFF → ALL → ONE → OFF, the usual player cycle. */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun mediaItem(serverUrl: String, album: AlbumDto, track: TrackDto): MediaItem =
        mediaItem(
            serverUrl = serverUrl,
            trackId = track.id,
            title = track.title,
            artistName = album.artist.name,
            albumTitle = album.title,
            coverUrl = album.coverUrl,
            isFavorite = track.isFavorite,
        )

    private fun mediaItem(serverUrl: String, track: QueueTrackDto): MediaItem =
        mediaItem(
            serverUrl = serverUrl,
            trackId = track.id,
            title = track.title,
            artistName = track.artistName,
            albumTitle = track.albumTitle,
            coverUrl = track.coverUrl,
            isFavorite = track.isFavorite,
        )

    private fun mediaItem(serverUrl: String, track: SavedTrack): MediaItem =
        mediaItem(
            serverUrl = serverUrl,
            trackId = track.id,
            title = track.title,
            artistName = track.artistName,
            albumTitle = track.albumTitle,
            coverUrl = track.coverUrl,
            isFavorite = track.isFavorite,
        )

    private fun mediaItem(
        serverUrl: String,
        trackId: Int,
        title: String,
        artistName: String,
        albumTitle: String,
        coverUrl: String?,
        isFavorite: Boolean,
    ): MediaItem {
        // Remembered so the queue can be serialized for the next app start:
        // MediaItem alone doesn't carry back the raw (relative) cover URL.
        savedTracks[trackId] = SavedTrack(trackId, title, artistName, albumTitle, coverUrl, isFavorite)
        val streamUrl = serverUrl.trimEnd('/') + "/api/player/tracks/$trackId/stream"
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistName)
            .setAlbumTitle(albumTitle)
            .setExtras(Bundle().apply { putBoolean(EXTRA_IS_FAVORITE, isFavorite) })
            .apply {
                resolveCover(serverUrl, coverUrl)?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(trackId.toString())
            .setUri(streamUrl)
            .setMediaMetadata(metadata)
            .build()
    }
}
