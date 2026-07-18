package com.jewelbox.player.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.PlaylistDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlaylistDetailUiState {
    data object Loading : PlaylistDetailUiState
    data class Error(val message: String?) : PlaylistDetailUiState
    data class Loaded(val serverUrl: String, val playlist: PlaylistDto) : PlaylistDetailUiState
}

class PlaylistDetailViewModel(private val playlistId: Int) : ViewModel() {

    private val repo = ServiceLocator.playlistRepository

    private val _state = MutableStateFlow<PlaylistDetailUiState>(PlaylistDetailUiState.Loading)
    val state: StateFlow<PlaylistDetailUiState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<PlaylistNotice?>(null)
    val notice: StateFlow<PlaylistNotice?> = _notice.asStateFlow()
    fun consumeNotice() { _notice.value = null }

    init {
        load()
    }

    fun load() {
        _state.value = PlaylistDetailUiState.Loading
        viewModelScope.launch {
            val serverUrl = repo.currentServerUrl()
            runCatching { repo.playlist(playlistId) }
                .onSuccess { _state.value = PlaylistDetailUiState.Loaded(serverUrl, it) }
                .onFailure { _state.value = PlaylistDetailUiState.Error(it.message) }
        }
    }

    private fun loaded(): PlaylistDetailUiState.Loaded? =
        _state.value as? PlaylistDetailUiState.Loaded

    fun rename(name: String) {
        viewModelScope.launch {
            runCatching { repo.renamePlaylist(playlistId, name) }
                .onSuccess { updated ->
                    _notice.value = PlaylistNotice.Renamed
                    loaded()?.let { _state.value = it.copy(playlist = updated) }
                }
                .onFailure { _notice.value = PlaylistNotice.Failed(it.message) }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.deletePlaylist(playlistId) }
                .onSuccess {
                    _notice.value = PlaylistNotice.Deleted
                    onDeleted()
                }
                .onFailure { _notice.value = PlaylistNotice.Failed(it.message) }
        }
    }

    fun removeEntry(entryId: Int) {
        viewModelScope.launch {
            runCatching { repo.removeEntry(playlistId, entryId) }
                .onSuccess { updated ->
                    _notice.value = PlaylistNotice.TrackRemoved
                    loaded()?.let { _state.value = it.copy(playlist = updated) }
                }
                .onFailure { _notice.value = PlaylistNotice.Failed(it.message) }
        }
    }

    /**
     * Swaps the track at [index] with its neighbour ([delta] = ±1), optimistic
     * with rollback like the PWA: the UI moves at once, the server order is the
     * source of truth on response or failure.
     */
    fun move(index: Int, delta: Int) {
        val before = loaded() ?: return
        val tracks = before.playlist.tracks.toMutableList()
        val target = index + delta
        if (index !in tracks.indices || target !in tracks.indices) return
        tracks[index] = tracks[target].also { tracks[target] = tracks[index] }
        _state.value = before.copy(playlist = before.playlist.copy(tracks = tracks))
        viewModelScope.launch {
            runCatching { repo.reorder(playlistId, tracks.mapNotNull { it.entryId }) }
                .onSuccess { updated ->
                    loaded()?.let { _state.value = it.copy(playlist = updated) }
                }
                .onFailure {
                    _state.value = before
                    _notice.value = PlaylistNotice.Failed(it.message)
                }
        }
    }

    /** Optimistic favorite flip, rolled back if the server refuses. */
    fun toggleFavorite(trackId: Int) {
        val before = loaded() ?: return
        val next = !(before.playlist.tracks.firstOrNull { it.id == trackId }?.isFavorite ?: return)
        fun apply(state: PlaylistDetailUiState.Loaded, value: Boolean) = state.copy(
            playlist = state.playlist.copy(
                tracks = state.playlist.tracks.map {
                    if (it.id == trackId) it.copy(isFavorite = value) else it
                },
            ),
        )
        _state.value = apply(before, next)
        viewModelScope.launch {
            runCatching { repo.setFavorite(trackId, next) }
                .onFailure {
                    loaded()?.let { _state.value = apply(it, !next) }
                    _notice.value = PlaylistNotice.Failed(it.message)
                }
        }
    }

    class Factory(private val playlistId: Int) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaylistDetailViewModel(playlistId) as T
    }
}
