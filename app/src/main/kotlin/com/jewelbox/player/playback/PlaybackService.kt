package com.jewelbox.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Foreground media service hosting the ExoPlayer instance. Media3 wires the
 * MediaSession to the system: media notification, lockscreen controls, Bluetooth
 * buttons — and playback survives the activity being killed, which is the whole
 * reason this app is native rather than a PWA.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

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

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback(player))
            .apply { sessionActivity?.let(::setSessionActivity) }
            .build()
    }

    /**
     * The app's own controller may also ask the player to pause at the end of
     * the current item (sleep timer "end of track"): an ExoPlayer setting the
     * Player interface — hence MediaController — doesn't carry.
     */
    private class SessionCallback(private val player: ExoPlayer) : MediaSession.Callback {
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    // App swiped away from recents: stop unless something is actively playing.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
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
