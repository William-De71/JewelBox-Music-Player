package com.jewelbox.player.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.PlaylistSummaryDto
import com.jewelbox.player.data.net.SmartPlaylistMetaDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlaylistsUiState {
    data object Loading : PlaylistsUiState
    data class Error(val message: String?) : PlaylistsUiState
    data class Loaded(
        val smart: List<SmartPlaylistMetaDto>,
        val playlists: List<PlaylistSummaryDto>,
    ) : PlaylistsUiState
}

class PlaylistsViewModel : ViewModel() {

    private val repo = ServiceLocator.playlistRepository

    private val _state = MutableStateFlow<PlaylistsUiState>(PlaylistsUiState.Loading)
    val state: StateFlow<PlaylistsUiState> = _state.asStateFlow()

    // One-shot notice for the snackbar; the screen consumes it after showing it.
    private val _notice = MutableStateFlow<PlaylistNotice?>(null)
    val notice: StateFlow<PlaylistNotice?> = _notice.asStateFlow()
    fun consumeNotice() { _notice.value = null }

    init {
        load()
    }

    /** [quiet] keeps the current list on screen while re-fetching (no spinner). */
    fun load(quiet: Boolean = false) {
        if (!quiet) _state.value = PlaylistsUiState.Loading
        viewModelScope.launch {
            runCatching {
                PlaylistsUiState.Loaded(
                    smart = repo.smartPlaylists(),
                    playlists = repo.playlists(),
                )
            }
                .onSuccess { _state.value = it }
                .onFailure { if (!quiet) _state.value = PlaylistsUiState.Error(it.message) }
        }
    }

    fun create(name: String, onCreated: (Int) -> Unit) {
        viewModelScope.launch {
            runCatching { repo.createPlaylist(name) }
                .onSuccess {
                    _notice.value = PlaylistNotice.Created
                    onCreated(it.id)
                }
                .onFailure { _notice.value = PlaylistNotice.Failed(it.message) }
        }
    }

    fun rename(id: Int, name: String) {
        viewModelScope.launch {
            runCatching { repo.renamePlaylist(id, name) }
                .onSuccess {
                    _notice.value = PlaylistNotice.Renamed
                    // Reload: the list is sorted by name server-side.
                    load(quiet = true)
                }
                .onFailure { _notice.value = PlaylistNotice.Failed(it.message) }
        }
    }

    fun delete(id: Int) {
        viewModelScope.launch {
            runCatching { repo.deletePlaylist(id) }
                .onSuccess {
                    _notice.value = PlaylistNotice.Deleted
                    load(quiet = true)
                }
                .onFailure { _notice.value = PlaylistNotice.Failed(it.message) }
        }
    }
}
