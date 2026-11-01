package com.jewelbox.player.data

import com.jewelbox.player.data.net.ApiClient
import com.jewelbox.player.data.net.DeviceQueueDto
import com.jewelbox.player.data.net.RemoteDeviceDto
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/**
 * This phone as a remote control of the other players: their list, their
 * queue, the commands. The live list comes from [com.jewelbox.player.playback.RemoteWatch].
 */
class RemoteRepository(private val prefs: ServerPrefs) {

    private suspend fun api() = ApiClient.create(prefs.serverUrl.first(), prefs.deviceId())

    suspend fun serverUrl(): String = prefs.serverUrl.first()

    suspend fun ownDeviceId(): String = prefs.deviceId()

    suspend fun devices(): List<RemoteDeviceDto> = api().remoteDevices().data

    /** The queue that player keeps on the server (what it is playing through). */
    suspend fun queue(deviceId: String): DeviceQueueDto = api().deviceQueue(deviceId)

    suspend fun send(deviceId: String, command: JsonObject) = api().remoteCommand(deviceId, command)
}
