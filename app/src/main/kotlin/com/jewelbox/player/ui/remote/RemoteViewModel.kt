package com.jewelbox.player.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.DeviceQueueDto
import com.jewelbox.player.data.net.RemoteDeviceDto
import com.jewelbox.player.playback.RemoteWatch
import com.jewelbox.player.playback.controllableOthers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * The other players this phone can drive, live, and the one being driven:
 * its state (from the watch stream), its queue (re-read when it moves on) and
 * the commands sent to it. Used by both remote screens.
 */
class RemoteViewModel : ViewModel() {

    private val repo = ServiceLocator.remoteRepository

    /** Null while the first list is on its way. */
    private val _devices = MutableStateFlow<List<RemoteDeviceDto>?>(null)
    val devices: StateFlow<List<RemoteDeviceDto>?> = _devices.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)

    private val _queue = MutableStateFlow<DeviceQueueDto?>(null)
    val queue: StateFlow<DeviceQueueDto?> = _queue.asStateFlow()

    /** A command the server refused or could not relay (toast). */
    private val _commandFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val commandFailed: SharedFlow<Unit> = _commandFailed.asSharedFlow()

    init {
        viewModelScope.launch {
            val url = repo.serverUrl()
            _serverUrl.value = url
            if (url.isBlank()) {
                _devices.value = emptyList()
                return@launch
            }
            val own = repo.ownDeviceId()
            RemoteWatch.devices(url).collect { list ->
                _devices.value = list?.let { controllableOthers(it, own) }
            }
        }
        // The queue of the driven player, read again whenever it changes track,
        // order or position in it (its server-side queue follows).
        viewModelScope.launch {
            _selectedId.filterNotNull()
                .collectLatest { id ->
                    _devices.filterNotNull()
                        .map { list -> list.firstOrNull { it.deviceId == id }?.state }
                        .map { state -> Triple(state?.trackId, state?.currentIndex, state?.shuffle) }
                        .distinctUntilChanged()
                        .collectLatest {
                            _queue.value = runCatching { repo.queue(id) }.getOrNull()
                        }
                }
        }
    }

    fun select(deviceId: String) {
        _selectedId.value = deviceId
    }

    /** To the selected player, or to [deviceId] when given (quick play/pause on the list). */
    fun send(command: JsonObject, deviceId: String? = null) {
        val target = deviceId ?: _selectedId.value ?: return
        viewModelScope.launch {
            runCatching { repo.send(target, command) }
                .onFailure { _commandFailed.tryEmit(Unit) }
        }
    }
}
