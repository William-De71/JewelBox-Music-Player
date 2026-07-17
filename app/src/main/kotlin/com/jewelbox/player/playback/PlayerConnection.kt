package com.jewelbox.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.AlbumDto
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

/** What the UI needs to render playback state (mini-player, current-track highlight). */
data class PlaybackUiState(
    val hasItem: Boolean = false,
    val isPlaying: Boolean = false,
    val currentTrackId: Int? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
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

    private var controller: MediaController? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _position = MutableStateFlow(PlaybackPosition())
    val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

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
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
                    onTrackStarted(mediaItem)
            })
            syncState(c)
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

    /** New track (or replay): reset scrobble bookkeeping and tell Last.fm "now playing". */
    private fun onTrackStarted(item: MediaItem?) {
        val trackId = item?.mediaId?.toIntOrNull()
        scrobbleTracker.onTrackStarted(trackId)
        if (trackId != null) {
            scope.launch { runCatching { ServiceLocator.albumRepository.nowPlaying(trackId) } }
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
        val startIndex = playable.indexOfFirst { it.id == startTrackId }.coerceAtLeast(0)
        val items = playable.map { track -> mediaItem(serverUrl, album, track) }
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
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

    private fun mediaItem(serverUrl: String, album: AlbumDto, track: TrackDto): MediaItem {
        val streamUrl = serverUrl.trimEnd('/') + "/api/player/tracks/${track.id}/stream"
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(album.artist.name)
            .setAlbumTitle(album.title)
            .apply {
                resolveCover(serverUrl, album.coverUrl)?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(streamUrl)
            .setMediaMetadata(metadata)
            .build()
    }
}
