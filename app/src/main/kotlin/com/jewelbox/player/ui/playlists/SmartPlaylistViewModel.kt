package com.jewelbox.player.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.QueueTrackDto
import com.jewelbox.player.playback.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SmartPlaylistUiState {
    data object Loading : SmartPlaylistUiState
    data class Error(val message: String?) : SmartPlaylistUiState
    data class Loaded(val serverUrl: String, val tracks: List<QueueTrackDto>) : SmartPlaylistUiState
}

class SmartPlaylistViewModel(private val smartKey: String) : ViewModel() {

    private val repo = ServiceLocator.playlistRepository

    private val _state = MutableStateFlow<SmartPlaylistUiState>(SmartPlaylistUiState.Loading)
    val state: StateFlow<SmartPlaylistUiState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<PlaylistNotice?>(null)
    val notice: StateFlow<PlaylistNotice?> = _notice.asStateFlow()
    fun consumeNotice() { _notice.value = null }

    init {
        load()
    }

    fun load() {
        _state.value = SmartPlaylistUiState.Loading
        viewModelScope.launch {
            val serverUrl = repo.currentServerUrl()
            runCatching { repo.smartPlaylist(smartKey) }
                .onSuccess { _state.value = SmartPlaylistUiState.Loaded(serverUrl, it.tracks) }
                .onFailure { _state.value = SmartPlaylistUiState.Error(it.message) }
        }
    }

    /** Dynamic mix only: asks the server for a completely new draw. */
    fun refreshMix() {
        _state.value = SmartPlaylistUiState.Loading
        viewModelScope.launch {
            val serverUrl = repo.currentServerUrl()
            runCatching { repo.refreshDynamicMix() }
                .onSuccess { _state.value = SmartPlaylistUiState.Loaded(serverUrl, it.tracks) }
                .onFailure { _state.value = SmartPlaylistUiState.Error(it.message) }
        }
    }

    /** Dynamic mix only: drops a disliked track; the playing queue follows. */
    fun removeMixTrack(trackId: Int) {
        viewModelScope.launch {
            runCatching { repo.removeDynamicMixTrack(trackId) }
                .onSuccess { res ->
                    (_state.value as? SmartPlaylistUiState.Loaded)
                        ?.let { _state.value = it.copy(tracks = res.tracks) }
                    PlayerConnection.onDynamicMixTrackRemoved(res.tracks)
                    _notice.value = PlaylistNotice.TrackRemoved
                }
                .onFailure { _notice.value = PlaylistNotice.Failed(it.message) }
        }
    }

    /** Optimistic favorite flip, rolled back if the server refuses. */
    fun toggleFavorite(trackId: Int) {
        val before = _state.value as? SmartPlaylistUiState.Loaded ?: return
        val next = !(before.tracks.firstOrNull { it.id == trackId }?.isFavorite ?: return)
        fun apply(state: SmartPlaylistUiState.Loaded, value: Boolean) = state.copy(
            tracks = state.tracks.map {
                if (it.id == trackId) it.copy(isFavorite = value) else it
            },
        )
        _state.value = apply(before, next)
        viewModelScope.launch {
            runCatching { repo.setFavorite(trackId, next) }
                .onFailure {
                    (_state.value as? SmartPlaylistUiState.Loaded)
                        ?.let { _state.value = apply(it, !next) }
                    _notice.value = PlaylistNotice.Failed(it.message)
                }
        }
    }

    class Factory(private val smartKey: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SmartPlaylistViewModel(smartKey) as T
    }
}
