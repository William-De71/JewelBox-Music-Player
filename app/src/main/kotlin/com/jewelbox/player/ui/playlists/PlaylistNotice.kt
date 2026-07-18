package com.jewelbox.player.ui.playlists

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jewelbox.player.R

/**
 * One-shot outcome of a playlist mutation, emitted by the ViewModels and
 * localized here for the snackbars (same split as SettingsViewModel.TestStatus:
 * typed status in the VM, strings in the UI).
 */
sealed interface PlaylistNotice {
    data object Created : PlaylistNotice
    data object Renamed : PlaylistNotice
    data object Deleted : PlaylistNotice
    data object TrackRemoved : PlaylistNotice
    data class TracksAdded(val count: Int) : PlaylistNotice
    data class Failed(val detail: String?) : PlaylistNotice
}

@Composable
fun noticeText(notice: PlaylistNotice): String = when (notice) {
    is PlaylistNotice.Created -> stringResource(R.string.playlist_created)
    is PlaylistNotice.Renamed -> stringResource(R.string.playlist_renamed)
    is PlaylistNotice.Deleted -> stringResource(R.string.playlist_deleted)
    is PlaylistNotice.TrackRemoved -> stringResource(R.string.track_removed)
    is PlaylistNotice.TracksAdded -> stringResource(R.string.tracks_added, notice.count)
    is PlaylistNotice.Failed -> stringResource(
        R.string.error_with_detail,
        notice.detail ?: stringResource(R.string.load_failed),
    )
}
