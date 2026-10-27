package com.jewelbox.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.playback.PlayerConnection
import com.jewelbox.player.ui.search.SearchLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.HomeRecentItemDto
import com.jewelbox.player.data.net.QueueDeviceDto
import com.jewelbox.player.playback.pickResumable

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object NoServer : HomeUiState

    /** The server predates 1.9 (no home endpoint), see SearchLogic.isServerTooOld. */
    data object ServerTooOld : HomeUiState
    data class Error(val message: String?) : HomeUiState
    data class Loaded(
        val serverUrl: String,
        val recent: List<HomeRecentItemDto>,
        val latest: List<AlbumDto>,
        val suggestions: List<AlbumDto>,
    ) : HomeUiState
}

class HomeViewModel : ViewModel() {

    private val repo = ServiceLocator.homeRepository

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    // Another device's recent queue, offered as "Reprendre depuis…" above the
    // feed (cross-device resume); null when there is nothing worth offering.
    private val _resumable = MutableStateFlow<QueueDeviceDto?>(null)
    val resumable: StateFlow<QueueDeviceDto?> = _resumable.asStateFlow()

    // A new draw of suggestions is in flight (refresh button disabled meanwhile).
    private val _refreshingSuggestions = MutableStateFlow(false)
    val refreshingSuggestions: StateFlow<Boolean> = _refreshingSuggestions.asStateFlow()

    // Devices dismissed this session, so a dismissed banner stays gone across
    // home refreshes until that device plays something new.
    private val dismissed = mutableSetOf<Pair<String, String?>>()

    init {
        // React to the configured server URL and every later change (e.g. after
        // the user saves an address in Settings): (re)load automatically.
        repo.serverUrl
            .distinctUntilChanged()
            .onEach { url -> loadFor(url) }
            .launchIn(viewModelScope)
        // Refresh the recent grid once a play has been recorded server-side.
        // Keyed on historyUpdated (fired after the POST), not on queueSource:
        // that StateFlow raced the POST and didn't re-fire for a replay of the
        // same item, so a freshly played list was missing until an app restart.
        PlayerConnection.historyUpdated
            .onEach { load() }
            .launchIn(viewModelScope)
    }

    /** Manual reload (used by the "Réessayer" button) against the current stored URL. */
    fun load() {
        viewModelScope.launch { loadFor(repo.currentServerUrl()) }
    }

    /**
     * Refresh button of the suggestions section: the server draws a new set and
     * keeps it for the day. [onError] gets true when the server predates 1.21.
     */
    fun refreshSuggestions(onError: (serverTooOld: Boolean) -> Unit) {
        if (_refreshingSuggestions.value) return
        _refreshingSuggestions.value = true
        viewModelScope.launch {
            runCatching { repo.refreshSuggestions() }
                .onSuccess { suggestions ->
                    (_state.value as? HomeUiState.Loaded)?.let { _state.value = it.copy(suggestions = suggestions) }
                }
                .onFailure { e -> onError(SearchLogic.isServerTooOld(e)) }
            _refreshingSuggestions.value = false
        }
    }

    /** Best-effort: an old server without the queue routes simply shows nothing. */
    private suspend fun refreshResumable() {
        val repo = ServiceLocator.queueSyncRepository
        val devices = runCatching { repo.otherDevices() }
            .getOrDefault(emptyList())
            .filter { (it.deviceId to it.updatedAt) !in dismissed }
        val ownUpdatedAt = if (devices.isEmpty()) null else runCatching { repo.ownQueue().updatedAt }.getOrNull()
        _resumable.value = pickResumable(
            devices, { it.trackCount }, { it.updatedAt },
            System.currentTimeMillis() / 1000, ownUpdatedAt,
        )
    }

    /** Loads that device's queue and plays it here, at the same track and position. */
    fun resume(device: QueueDeviceDto) {
        // Until this device's own queue is saved (and dated) on the server,
        // the next home refresh would offer the same queue again.
        dismissed += device.deviceId to device.updatedAt
        _resumable.value = null
        viewModelScope.launch {
            val serverUrl = repo.currentServerUrl()
            val queue = runCatching { ServiceLocator.queueSyncRepository.deviceQueue(device.deviceId) }
                .getOrNull() ?: return@launch
            PlayerConnection.resumeQueue(serverUrl, queue)
        }
    }

    fun dismissResume(device: QueueDeviceDto) {
        dismissed += device.deviceId to device.updatedAt
        _resumable.value = null
    }

    private suspend fun loadFor(serverUrl: String) {
        if (serverUrl.isBlank()) {
            _state.value = HomeUiState.NoServer
            return
        }
        if (_state.value !is HomeUiState.Loaded) {
            _state.value = HomeUiState.Loading
        }
        refreshResumable()
        runCatching { repo.home() }
            .onSuccess { home ->
                // Best-effort like on desktop: the "latest added" row stays empty
                // if that extra call fails, without breaking the rest of the feed.
                val latest = runCatching { repo.latestAlbums() }.getOrDefault(emptyList())
                _state.value = HomeUiState.Loaded(
                    serverUrl = serverUrl,
                    recent = home.recent,
                    latest = latest,
                    suggestions = home.suggestions,
                )
            }
            .onFailure { e ->
                _state.value = if (SearchLogic.isServerTooOld(e)) {
                    HomeUiState.ServerTooOld
                } else {
                    HomeUiState.Error(e.message)
                }
            }
    }
}
