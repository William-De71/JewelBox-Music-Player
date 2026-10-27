package com.jewelbox.player.playback

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.jewelbox.player.R

/**
 * Runs the downloads of [Offline] in the background (foreground service with
 * its progress notification), and lets the system restart them when the
 * network they wait for (Wi-Fi by default) comes back.
 */
@OptIn(UnstableApi::class)
class JewelBoxDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    Offline.NOTIFICATION_CHANNEL,
    R.string.downloads_channel,
    R.string.downloads_channel_description,
) {

    override fun getDownloadManager(): DownloadManager {
        Offline.init(this)
        return Offline.manager
    }

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        Offline.notifications.buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            null,
            getString(R.string.downloads_notification),
            downloads,
            notMetRequirements,
        )

    private companion object {
        const val NOTIFICATION_ID = 2
        const val JOB_ID = 1
    }
}
