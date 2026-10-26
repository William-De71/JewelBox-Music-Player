package com.jewelbox.player.data

import com.jewelbox.player.data.net.ApiClient
import com.jewelbox.player.data.net.DeviceQueueDto
import com.jewelbox.player.data.net.QueueDeviceDto
import com.jewelbox.player.data.net.QueueStateBody
import com.jewelbox.player.data.net.QueueTrackDto
import com.jewelbox.player.data.net.SaveQueueBody
import kotlinx.coroutines.flow.first

/**
 * The server-side, per-device playback queue (cross-device resume). Every call
 * carries this install's X-Device-Id; the URL is re-read on each call like the
 * other repositories, so a change in Settings takes effect immediately.
 */
class QueueSyncRepository(private val prefs: ServerPrefs) {

    private suspend fun api() = ApiClient.create(prefs.serverUrl.first(), prefs.deviceId())

    suspend fun saveQueue(
        trackIds: List<Int>,
        currentIndex: Int,
        positionSec: Double,
        deviceLabel: String,
        sourceLabel: String? = null,
    ) {
        api().saveQueue(SaveQueueBody(trackIds, currentIndex, positionSec, deviceLabel, sourceLabel))
    }

    suspend fun updateState(currentIndex: Int, positionSec: Double) =
        api().updateQueueState(QueueStateBody(currentIndex, positionSec))

    suspend fun clear() = api().clearQueue()

    suspend fun reportLiveState(state: com.jewelbox.player.playback.LiveState) = api().reportLiveState(state)

    suspend fun track(trackId: Int): QueueTrackDto = api().track(trackId)

    suspend fun radio(albumId: Int? = null, artistId: Int? = null) = api().radio(albumId, artistId)

    /** Queues left by the other devices, most recent first (the server excludes this one). */
    suspend fun otherDevices(): List<QueueDeviceDto> = api().queueDevices().data

    suspend fun deviceQueue(deviceId: String): DeviceQueueDto = api().deviceQueue(deviceId)

    suspend fun ownQueue(): DeviceQueueDto = api().ownQueue()
}
