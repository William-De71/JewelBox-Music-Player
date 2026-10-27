package com.jewelbox.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import com.jewelbox.player.data.net.DeviceQueueDto
import com.jewelbox.player.data.net.QueueTrackDto
import com.jewelbox.player.data.net.ReplayGainDto
import com.jewelbox.player.data.net.TrackDto
import com.jewelbox.player.data.offline.cacheKey
import com.jewelbox.player.data.resolveCover
import com.jewelbox.player.ui.playlists.DYNAMIC_MIX_KEY
import com.jewelbox.player.ui.playlists.smartSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    /** MediaMetadata extras keys carrying the track's ReplayGain (dB), when tagged. */
    private const val EXTRA_RG_TRACK_GAIN = "jewelbox.rg_track_gain"
    private const val EXTRA_RG_ALBUM_GAIN = "jewelbox.rg_album_gain"

    // ReplayGain rides on the player volume: userVolume is what the user (the
    // remote controller) set, the player gets it scaled for the current track.
    private var userVolume = 1f
    private var replayGainMode = ReplayGainMode.DEFAULT

    // Sleep timer (SleepTimer.kt); sleepFade scales the volume over its last seconds.
    private val _sleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer: StateFlow<SleepTimer?> = _sleepTimer.asStateFlow()
    private var sleepFade = 1f
    private var sleepJob: Job? = null

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

    // Emitted after a play has been recorded server-side, so the home screen can
    // refresh its "recently played" section once the new entry actually exists.
    // A SharedFlow (not StateFlow) so replaying the same item still fires, and so
    // the refresh happens after the POST — not racing it like queueSource did.
    private val _historyUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val historyUpdated: SharedFlow<Unit> = _historyUpdated.asSharedFlow()

    // Emitted with the server's fresh dynamic mix list after each rotation (a
    // track played through), so an open dynamic mix screen shows the same list
    // as the queue instead of the one it loaded.
    private val _dynamicMixUpdated = MutableSharedFlow<List<QueueTrackDto>>(extraBufferCapacity = 1)
    val dynamicMixUpdated: SharedFlow<List<QueueTrackDto>> = _dynamicMixUpdated.asSharedFlow()

    // Scrobble rules live in ScrobbleTracker (pure, unit-tested); this object only
    // feeds it player positions and fires the network calls it requests.
    private val scrobbleTracker = ScrobbleTracker()

    // Cross-device resume: the queue is mirrored to the server's per-device
    // queue. When to send what is decided by QueueSyncPlanner (pure, tested).
    private val queueSyncPlanner = QueueSyncPlanner()
    private var pendingQueueSave: Job? = null

    // What the queue was started from (album title, playlist name, smart list
    // label), sent along so the other devices can say what they would resume.
    private var sourceLabel: String? = null
    private var appContext: Context? = null

    /** Delay grouping a burst of queue edits (pick an album, skip twice…) into one PUT. */
    private const val QUEUE_SAVE_DELAY_MS = 1500L

    // Remote control (Gladys…): live state reports, decided by LiveReporter
    // (pure, tested); the command stream lives in RemoteControl.
    private val liveReporter = LiveReporter()

    /** Name the other devices (and the remote controllers) see. */
    val deviceLabel: String
        get() = "JewelBox Android · ${Build.MODEL}"

    fun init(context: Context) {
        if (controller != null) return
        appContext = context.applicationContext
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    syncState(player)
                    syncServerQueue(player)
                    reportLiveState(player)
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // AUTO means the previous item played through to its end —
                    // the trigger for the dynamic mix rotation (seeks/skips don't count).
                    val endedId = lastTrackId
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && endedId != null) {
                        onTrackEnded(endedId, queueExhausted = false)
                    }
                    applyVolume(c)
                    onTrackStarted(mediaItem)
                    trimDynamicMix(c)
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

                // Sleep timer "end of track": the player paused itself there.
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                        _sleepTimer.value == SleepTimer.EndOfTrack
                    ) {
                        setSleepTimer(null)
                    }
                }
            })
            syncState(c)
            scope.launch {
                ServiceLocator.serverPrefs.replayGain.collect { mode ->
                    replayGainMode = mode
                    controller?.let(::applyVolume)
                }
            }
            restoreSavedQueue()
            startTicker()
            RemoteControl.start(
                scope,
                onOpened = {
                    // The controller just saw this player appear: tell it where playback is.
                    liveReporter.forget()
                    controller?.let(::reportLiveState)
                },
                onCommand = ::onRemoteCommand,
            )
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
                updateSourceLabel(defaultSourceLabel(_queueSource.value))
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
            _dynamicMixUpdated.tryEmit(fresh.tracks)
            // Another queue may have taken over while the request was in flight.
            if (dynamicMix) syncDynamicMixQueue(fresh.tracks, restart = queueExhausted)
        }
    }

    private var mixTrimming = false

    /** Skipping by hand through the mix: far enough, the skipped tracks are consumed too (see [DynamicMixSync.trimIds]). */
    private fun trimDynamicMix(c: Player) {
        if (!dynamicMix || mixTrimming) return
        val trackIds = DynamicMixSync.trimIds(playedBefore(c))
        if (trackIds.isEmpty()) return
        mixTrimming = true
        scope.launch {
            var fresh: List<QueueTrackDto>? = null
            try {
                for (id in trackIds) {
                    fresh = runCatching { ServiceLocator.playlistRepository.dynamicMixPlayed(id) }
                        .getOrNull()?.tracks ?: break
                }
            } finally {
                mixTrimming = false
            }
            val tracks = fresh ?: return@launch
            _dynamicMixUpdated.tryEmit(tracks)
            if (dynamicMix) syncDynamicMixQueue(tracks, restart = false)
        }
    }

    /** Track ids before the current item, in play order (shuffle included). */
    private fun playedBefore(c: Player): List<Int> {
        val timeline = c.currentTimeline
        val current = c.currentMediaItemIndex
        if (timeline.isEmpty || current == C.INDEX_UNSET) return emptyList()
        val shuffle = c.shuffleModeEnabled
        val ids = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(shuffle)
        while (index != C.INDEX_UNSET && index != current) {
            c.getMediaItemAt(index).mediaId.toIntOrNull()?.let(ids::add)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        return if (index == current) ids else emptyList()
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
        // Periodic progress for the server-side queue (the planner throttles it).
        syncServerQueue(c)
        reportLiveState(c)

        // Checkpoint the resume position while playing, rarely enough not to
        // hammer DataStore: losing at most 5s of progress is imperceptible.
        if (c.isPlaying && ++ticksSinceQueueSave >= POSITION_SAVE_INTERVAL_TICKS) {
            ticksSinceQueueSave = 0
            saveQueue()
        }

        val due = scrobbleTracker.onTick(positionMs / 1000.0, durationMs / 1000.0) ?: return
        // Counter then Last.fm; kept on the phone while the server is away.
        Offline.reportPlay(due.trackId, due.startedAt)
    }

    /**
     * Queues every playable track of [album] (those with an audio file) and starts
     * playback at [startTrackId].
     */
    fun playAlbum(serverUrl: String, album: AlbumDto, startTrackId: Int, offlineScope: String? = null) {
        val c = controller ?: return
        val playable = album.tracks.filter { it.hasFile }
        if (playable.isEmpty()) return
        dynamicMix = false
        queueServerUrl = serverUrl
        _queueSource.value = QueueSource.Album(album.id)
        updateSourceLabel(album.title)
        reportPlayStarted(QueueSource.Album(album.id))
        val startIndex = playable.indexOfFirst { it.id == startTrackId }.coerceAtLeast(0)
        val trackScope = offlineScope ?: Offline.currentScope.value
        val items = playable.map { track -> mediaItem(serverUrl, album, track, trackScope) }
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
        startPositionMs: Long = 0L,
        sourceLabel: String? = null,
        offlineScope: String? = null,
    ) {
        val c = controller ?: return
        val playable = tracks.filter { it.hasFile }
        if (playable.isEmpty()) return
        this.dynamicMix = dynamicMix
        queueServerUrl = serverUrl
        _queueSource.value = source
        updateSourceLabel(sourceLabel ?: defaultSourceLabel(source))
        reportPlayStarted(source)
        val trackScope = offlineScope ?: Offline.currentScope.value
        val items = playable.map { mediaItem(serverUrl, it, trackScope) }
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), startPositionMs.coerceAtLeast(0L))
        c.prepare()
        c.play()
        saveQueue()
    }

    /**
     * Picks up the queue another device left on the server: same tracks, same
     * current track, same position within it. Returns false when nothing in
     * it is playable here.
     */
    fun resumeQueue(serverUrl: String, queue: DeviceQueueDto): Boolean {
        val start = resumeStart(queue.tracks.map { it.hasFile }, queue.currentIndex) ?: return false
        playQueue(
            serverUrl, queue.tracks,
            startIndex = start.index,
            startPositionMs = if (start.sameTrack) (queue.positionSec * 1000).toLong() else 0L,
            // Keep the original source ("Mix dynamique", an album…) on this device too.
            sourceLabel = queue.sourceLabel,
        )
        return true
    }

    /** Label of a source when the caller didn't name it: smart lists have fixed labels. */
    private fun defaultSourceLabel(source: QueueSource?): String? {
        val key = (source as? QueueSource.Smart)?.key ?: return null
        val spec = smartSpec(key) ?: return null
        return appContext?.getString(spec.label)
    }

    private fun updateSourceLabel(label: String?) {
        if (label == sourceLabel) return
        sourceLabel = label
        // Same tracks, new source (e.g. resuming the very queue restored at
        // start-up): the server must still learn the new label.
        queueSyncPlanner.resend()
    }

    /**
     * Mirrors the queue to the server for cross-device resume. Best-effort and
     * silent: an old server without these routes, or an unreachable one, must
     * never disturb playback. A full save is deferred a moment so a burst of
     * edits makes a single PUT, built from the player's state at send time.
     */
    private fun syncServerQueue(player: Player) {
        if (restoring || queueServerUrl == null) return
        val ids = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toIntOrNull() }
        val action = queueSyncPlanner.update(
            trackIds = ids,
            currentIndex = if (player.mediaItemCount > 0) player.currentMediaItemIndex else null,
            positionSec = player.currentPosition / 1000.0,
            isPlaying = player.isPlaying,
            nowSec = SystemClock.elapsedRealtime() / 1000.0,
        ) ?: return
        val repo = ServiceLocator.queueSyncRepository
        when (action) {
            is SyncAction.Save -> {
                pendingQueueSave?.cancel()
                pendingQueueSave = scope.launch {
                    delay(QUEUE_SAVE_DELAY_MS)
                    val c = controller ?: return@launch
                    val trackIds = (0 until c.mediaItemCount)
                        .mapNotNull { c.getMediaItemAt(it).mediaId.toIntOrNull() }
                    if (trackIds.isEmpty()) return@launch
                    runCatching {
                        repo.saveQueue(
                            trackIds, c.currentMediaItemIndex,
                            c.currentPosition / 1000.0, deviceLabel, sourceLabel,
                        )
                    }
                }
            }
            // A pending PUT will carry the latest state anyway.
            is SyncAction.State -> if (pendingQueueSave?.isActive != true) {
                scope.launch { runCatching { repo.updateState(action.currentIndex, action.positionSec) } }
            }
            SyncAction.Clear -> {
                pendingQueueSave?.cancel()
                scope.launch { runCatching { repo.clear() } }
            }
        }
    }

    // Convenience launchers for tiles/cards that only carry an id or a key (home,
    // library grids): fetch the full item, then play it. Fire-and-forget and
    // failure-tolerant — a load error just leaves playback untouched.
    fun playAlbumById(serverUrl: String, albumId: Int) {
        scope.launch {
            val album = runCatching { ServiceLocator.albumRepository.album(albumId) }.getOrNull() ?: return@launch
            val firstPlayable = album.tracks.firstOrNull { it.hasFile } ?: return@launch
            playAlbum(serverUrl, album, firstPlayable.id)
        }
    }

    fun playPlaylistById(serverUrl: String, playlistId: Int) {
        scope.launch {
            val playlist = runCatching { ServiceLocator.playlistRepository.playlist(playlistId) }.getOrNull() ?: return@launch
            playQueue(
                serverUrl, playlist.tracks,
                source = QueueSource.Playlist(playlistId), sourceLabel = playlist.name,
            )
        }
    }

    fun playSmartByKey(serverUrl: String, key: String) {
        scope.launch {
            val smart = runCatching { ServiceLocator.playlistRepository.smartPlaylist(key) }.getOrNull() ?: return@launch
            playQueue(
                serverUrl, smart.tracks,
                dynamicMix = key == DYNAMIC_MIX_KEY,
                source = QueueSource.Smart(key),
            )
        }
    }

    // Feeds the home screen's "recently played" section. Fire-and-forget: an
    // old server (400/404) or an unreachable one must never disturb playback.
    // Albums and playlists are keyed by id; smart playlists by their text key.
    private fun reportPlayStarted(source: QueueSource?) {
        if (source == null) return
        scope.launch {
            val recorded = runCatching {
                when (source) {
                    is QueueSource.Album -> ServiceLocator.homeRepository.reportPlay("album", source.albumId)
                    is QueueSource.Playlist -> ServiceLocator.homeRepository.reportPlay("playlist", source.playlistId)
                    is QueueSource.Smart -> ServiceLocator.homeRepository.reportSmartPlay(source.key)
                }
            }.isSuccess
            // Refresh the home feed only once the entry is actually recorded, so
            // the GET doesn't race the POST (which left the new item missing).
            if (recorded) _historyUpdated.emit(Unit)
        }
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

    /** Player volume = user volume × the current track's ReplayGain factor. */
    private fun applyVolume(player: Player) {
        val extras = player.currentMediaItem?.mediaMetadata?.extras
        fun gain(key: String) = extras?.takeIf { it.containsKey(key) }?.getDouble(key)
        val volume = userVolume * sleepFade *
            replayGainFactor(gain(EXTRA_RG_TRACK_GAIN), gain(EXTRA_RG_ALBUM_GAIN), replayGainMode)
        if (player.volume != volume) player.volume = volume
    }

    /**
     * Starts, replaces or (null) cancels the sleep timer. A timed one fades
     * out and pauses, counting on while paused; "end of track" has the player
     * itself pause at the end of the current item.
     */
    fun setSleepTimer(timer: SleepTimer?) {
        sleepJob?.cancel()
        sleepJob = null
        _sleepTimer.value = timer
        sleepFade = 1f
        val c = controller ?: return
        applyVolume(c)
        c.sendCustomCommand(
            PlaybackService.PAUSE_AT_END_COMMAND,
            Bundle().apply { putBoolean(PlaybackService.PAUSE_AT_END_ENABLED, timer == SleepTimer.EndOfTrack) },
        )
        reportLiveState(c)
        if (timer !is SleepTimer.Timed) return
        sleepJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                if (now >= timer.endsAtMs) {
                    controller?.pause()
                    setSleepTimer(null)
                    return@launch
                }
                val fade = sleepFadeFactor(timer, now)
                if (fade != sleepFade) {
                    sleepFade = fade
                    controller?.let(::applyVolume)
                }
                delay(250)
            }
        }
    }

    // ── Remote control ──────────────────────────────────────────────────────

    private fun reportLiveState(player: Player) {
        if (restoring) return
        val item = player.currentMediaItem
        val live = liveStateOf(
            hasItem = item != null,
            isPlaying = player.isPlaying,
            trackId = item?.mediaId?.toIntOrNull(),
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            volume = userVolume,
            shuffle = player.shuffleModeEnabled,
            repeat = _state.value.repeatMode,
            deviceLabel = deviceLabel,
            sleepTimer = _sleepTimer.value,
        )
        if (!liveReporter.update(live, SystemClock.elapsedRealtime() / 1000.0)) return
        scope.launch { runCatching { ServiceLocator.queueSyncRepository.reportLiveState(live) } }
    }

    /** One command from the remote-control stream, already validated by the server. */
    private fun onRemoteCommand(command: JsonObject) {
        val c = controller ?: return
        fun arg(name: String) = command[name]?.jsonPrimitive
        when (command["command"]?.jsonPrimitive?.content) {
            "play" -> play(c)
            "pause" -> c.pause()
            "toggle" -> if (c.isPlaying) c.pause() else play(c)
            "next" -> next()
            "previous" -> previous()
            "seek" -> arg("position_sec")?.doubleOrNull?.let { c.seekTo((it * 1000).toLong()) }
            "volume" -> arg("volume")?.doubleOrNull?.let {
                userVolume = (it / 100).toFloat().coerceIn(0f, 1f)
                applyVolume(c)
            }
            "shuffle" -> arg("shuffle")?.booleanOrNull?.let { c.shuffleModeEnabled = it }
            "sleep_timer" -> setSleepTimer(
                sleepTimerFrom(arg("minutes")?.intOrNull, arg("end_of_track")?.booleanOrNull == true, System.currentTimeMillis()),
            )
            "repeat" -> c.repeatMode = when (arg("repeat")?.content) {
                "all" -> Player.REPEAT_MODE_ALL
                "one" -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            "play_index" -> arg("index")?.intOrNull?.takeIf { it in 0 until c.mediaItemCount }?.let { index ->
                // On the current track, just make sure it plays (no restart).
                if (index != c.currentMediaItemIndex) c.seekTo(index, 0L)
                play(c)
            }
            "play_media" -> {
                val media = command["media"]?.jsonObject ?: return
                val type = media["type"]?.jsonPrimitive?.content ?: return
                val id = media["id"]?.jsonPrimitive ?: return
                playMedia(type, id.content, id.intOrNull, arg("mode")?.content ?: "replace")
            }
        }
    }

    private fun play(c: MediaController) {
        if (c.mediaItemCount == 0) return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    private fun playMedia(type: String, key: String, id: Int?, mode: String) {
        scope.launch {
            val serverUrl = ServiceLocator.serverPrefs.serverUrl.first().ifBlank { return@launch }
            val enqueue = mode == "next" || mode == "append"
            runCatching {
                when (type) {
                    "album" -> {
                        val album = ServiceLocator.albumRepository.album(id ?: return@launch)
                        val playable = album.tracks.filter { it.hasFile }
                        val first = playable.firstOrNull() ?: return@launch
                        if (enqueue && enqueueItems(serverUrl, playable.map { mediaItem(serverUrl, album, it) }, mode == "next")) {
                            return@launch
                        }
                        playAlbum(serverUrl, album, first.id)
                    }
                    "playlist" -> {
                        val playlist = ServiceLocator.playlistRepository.playlist(id ?: return@launch)
                        playOrEnqueue(serverUrl, playlist.tracks, mode) {
                            playQueue(serverUrl, playlist.tracks, source = QueueSource.Playlist(playlist.id), sourceLabel = playlist.name)
                        }
                    }
                    "smart" -> {
                        val smart = ServiceLocator.playlistRepository.smartPlaylist(key)
                        playOrEnqueue(serverUrl, smart.tracks, mode) {
                            playQueue(serverUrl, smart.tracks, dynamicMix = key == DYNAMIC_MIX_KEY, source = QueueSource.Smart(key))
                        }
                    }
                    "track" -> {
                        val track = ServiceLocator.queueSyncRepository.track(id ?: return@launch)
                        playOrEnqueue(serverUrl, listOf(track), mode) { playQueue(serverUrl, listOf(track)) }
                    }
                    "album_radio", "artist_radio" -> {
                        val radio = ServiceLocator.queueSyncRepository.radio(
                            albumId = if (type == "album_radio") id else null,
                            artistId = if (type == "artist_radio") id else null,
                        )
                        val label = appContext?.getString(com.jewelbox.player.R.string.remote_radio_label, radio.seed?.name.orEmpty())
                        playOrEnqueue(serverUrl, radio.tracks, mode) { playQueue(serverUrl, radio.tracks, sourceLabel = label) }
                    }
                }
            }
        }
    }

    private fun playOrEnqueue(serverUrl: String, tracks: List<QueueTrackDto>, mode: String, replace: () -> Unit) {
        val playable = tracks.filter { it.hasFile }
        if (playable.isEmpty()) return
        val enqueue = mode == "next" || mode == "append"
        if (enqueue && enqueueItems(serverUrl, playable.map { mediaItem(serverUrl, it) }, mode == "next")) return
        replace()
    }

    /** "Play next" / "add to queue". False when nothing is loaded: the caller starts the queue instead. */
    private fun enqueueItems(serverUrl: String, items: List<MediaItem>, next: Boolean): Boolean {
        val c = controller ?: return true
        if (c.mediaItemCount == 0) return false
        if (queueServerUrl == null) queueServerUrl = serverUrl
        if (next) c.addMediaItems(c.currentMediaItemIndex + 1, items) else c.addMediaItems(items)
        saveQueue()
        return true
    }

    private fun mediaItem(
        serverUrl: String,
        album: AlbumDto,
        track: TrackDto,
        scope: String? = Offline.currentScope.value,
    ): MediaItem =
        mediaItem(
            serverUrl = serverUrl,
            trackId = track.id,
            title = track.title,
            artistName = album.artist.name,
            albumTitle = album.title,
            coverUrl = album.coverUrl,
            isFavorite = track.isFavorite,
            replayGain = track.replaygain,
            scope = scope,
        )

    private fun mediaItem(
        serverUrl: String,
        track: QueueTrackDto,
        scope: String? = Offline.currentScope.value,
    ): MediaItem =
        mediaItem(
            serverUrl = serverUrl,
            trackId = track.id,
            title = track.title,
            artistName = track.artistName,
            albumTitle = track.albumTitle,
            coverUrl = track.coverUrl,
            isFavorite = track.isFavorite,
            replayGain = track.replaygain,
            scope = scope,
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
            replayGain = track.replaygain,
            scope = track.scope ?: Offline.currentScope.value,
        )

    // setCustomCacheKey: the key of the download library (offline listening).
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun mediaItem(
        serverUrl: String,
        trackId: Int,
        title: String,
        artistName: String,
        albumTitle: String,
        coverUrl: String?,
        isFavorite: Boolean,
        replayGain: ReplayGainDto?,
        scope: String?,
    ): MediaItem {
        // Remembered so the queue can be serialized for the next app start:
        // MediaItem alone doesn't carry back the raw (relative) cover URL.
        savedTracks[trackId] = SavedTrack(trackId, title, artistName, albumTitle, coverUrl, isFavorite, replayGain, scope)
        val streamUrl = serverUrl.trimEnd('/') + "/api/player/tracks/$trackId/stream"
        val extras = Bundle().apply {
            putBoolean(EXTRA_IS_FAVORITE, isFavorite)
            replayGain?.trackGain?.let { putDouble(EXTRA_RG_TRACK_GAIN, it) }
            replayGain?.albumGain?.let { putDouble(EXTRA_RG_ALBUM_GAIN, it) }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistName)
            .setAlbumTitle(albumTitle)
            .setExtras(extras)
            .apply {
                // The cover kept for offline listening first: it shows without the server.
                (Offline.localCover(coverUrl) ?: resolveCover(serverUrl, coverUrl))
                    ?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(trackId.toString())
            .setUri(streamUrl)
            // A downloaded track is read from the phone (same key as its download).
            .apply { scope?.let { setCustomCacheKey(cacheKey(it, trackId)) } }
            .setMediaMetadata(metadata)
            .build()
    }
}
