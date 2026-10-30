package com.jewelbox.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground media service hosting the ExoPlayer instance. Media3 wires the
 * MediaSession to the system: media notification, lockscreen controls, Bluetooth
 * buttons — and playback survives the activity being killed, which is the whole
 * reason this app is native rather than a PWA. A library service, so Android
 * Auto can browse the collection (AutoLibrary.kt) and play from it.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            // Proper audio focus: pause for calls, duck for notifications.
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            // Pause when headphones are unplugged.
            .setHandleAudioBecomingNoisy(true)
            // Streaming over LAN/VPN: keep network alive while playing.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Downloaded tracks play from the phone (offline listening).
            .setMediaSourceFactory(DefaultMediaSourceFactory(Offline.playerDataSourceFactory()))
            .build()

        // Tapping the media notification reopens the app.
        val sessionActivity = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        mediaSession = MediaLibrarySession.Builder(this, player, SessionCallback(player, AutoLibrary(this), scope))
            .apply { sessionActivity?.let(::setSessionActivity) }
            .build()
    }

    /**
     * The app's own controller may also ask the player to pause at the end of
     * the current item (sleep timer "end of track"): an ExoPlayer setting the
     * Player interface — hence MediaController — doesn't carry.
     */
    private class SessionCallback(
        private val player: ExoPlayer,
        private val library: AutoLibrary,
        private val scope: CoroutineScope,
    ) : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != session.token.packageName) return super.onConnect(session, controller)
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(PAUSE_AT_END_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != PAUSE_AT_END_ACTION) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            player.pauseAtEndOfMediaItems = args.getBoolean(PAUSE_AT_END_ENABLED)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // ── Android Auto ─────────────────────────────────────────────────────

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(library.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            future { library.children(parentId, page, pageSize) }

        /**
         * Auto only sends the media id of what was tapped (or a voice query):
         * the library turns it into the real queue. The app's own controller
         * sends complete items, which go through unchanged.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.any { it.localConfiguration != null }) {
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
            return future {
                library.resolve(mediaItems)?.toSessionItems()
                    ?: throw UnsupportedOperationException("Nothing playable for ${mediaItems.map { it.mediaId }}")
            }
        }

        /** Runs [block] on the main thread; its failure fails the future. */
        private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
            val result = SettableFuture.create<T>()
            scope.launch {
                runCatching { block() }.fold(onSuccess = { result.set(it) }, onFailure = { result.setException(it) })
            }
            return result
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    // App swiped away from recents: stop unless something is actively playing.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val PAUSE_AT_END_ACTION = "jewelbox.pause_at_end"
        const val PAUSE_AT_END_ENABLED = "enabled"
        val PAUSE_AT_END_COMMAND = SessionCommand(PAUSE_AT_END_ACTION, Bundle.EMPTY)
    }
}
