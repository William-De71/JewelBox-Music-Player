package com.jewelbox.player.ui.albumdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.PlaylistSummaryDto
import com.jewelbox.player.ui.playlists.PlaylistNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AlbumDetailUiState {
    data object Loading : AlbumDetailUiState
    data class Error(val message: String?) : AlbumDetailUiState
    data class Loaded(val serverUrl: String, val album: AlbumDto) : AlbumDetailUiState
}

/**
 * State of the "add to playlist" bottom sheet: the target (one track or the
 * whole album) plus the playlists to choose from, loaded when it opens.
 */
data class AddToPlaylistSheet(
    val trackId: Int?, // null = the whole album
    val playlists: List<PlaylistSummaryDto>? = null, // null while loading
)

class AlbumDetailViewModel(private val albumId: Int) : ViewModel() {

    private val repo = ServiceLocator.albumRepository
    private val playlistRepo = ServiceLocator.playlistRepository

    private val _state = MutableStateFlow<AlbumDetailUiState>(AlbumDetailUiState.Loading)
    val state: StateFlow<AlbumDetailUiState> = _state.asStateFlow()

    private val _sheet = MutableStateFlow<AddToPlaylistSheet?>(null)
    val sheet: StateFlow<AddToPlaylistSheet?> = _sheet.asStateFlow()

    // One-shot notice for the snackbar; consumed by the screen after display.
    private val _notice = MutableStateFlow<PlaylistNotice?>(null)
    val notice: StateFlow<PlaylistNotice?> = _notice.asStateFlow()
    fun consumeNotice() { _notice.value = null }

    init {
        load()
    }

    /** Optimistic favorite flip of one track of the album, rolled back on failure. */
    fun toggleFavorite(trackId: Int) {
        val before = _state.value as? AlbumDetailUiState.Loaded ?: return
        val next = !(before.album.tracks.firstOrNull { it.id == trackId }?.isFavorite ?: return)
        fun apply(state: AlbumDetailUiState.Loaded, value: Boolean) = state.copy(
            album = state.album.copy(
                tracks = state.album.tracks.map {
                    if (it.id == trackId) it.copy(isFavorite = value) else it
                },
            ),
        )
        _state.value = apply(before, next)
        viewModelScope.launch {
            runCatching { playlistRepo.setFavorite(trackId, next) }
                .onFailure {
                    (_state.value as? AlbumDetailUiState.Loaded)
                        ?.let { _state.value = apply(it, !next) }
                    _notice.value = PlaylistNotice.Failed(it.message)
                }
        }
    }

    /** Opens the "add to playlist" sheet for one track, or the album when null. */
    fun openAddToPlaylist(trackId: Int? = null) {
        _sheet.value = AddToPlaylistSheet(trackId)
        viewModelScope.launch {
            runCatching { playlistRepo.playlists() }
                .onSuccess { lists ->
                    // Still the same request? (the sheet may have been dismissed)
                    _sheet.value = _sheet.value?.takeIf { it.trackId == trackId }
                        ?.copy(playlists = lists)
                }
                .onFailure {
                    _sheet.value = null
                    _notice.value = PlaylistNotice.Failed(it.message)
                }
        }
    }

    fun dismissAddToPlaylist() {
        _sheet.value = null
    }

    fun addToPlaylist(playlistId: Int) {
        val target = _sheet.value ?: return
        _sheet.value = null
        viewModelScope.launch {
            runCatching {
                if (target.trackId != null) playlistRepo.addTrack(playlistId, target.trackId)
                else playlistRepo.addAlbum(playlistId, albumId)
            }
                .onSuccess { _notice.value = PlaylistNotice.TracksAdded(it.added) }
                .onFailure { _notice.value = PlaylistNotice.Failed(it.message) }
        }
    }

    /** "Créer et ajouter" from the sheet: new playlist, then the add above. */
    fun createPlaylistAndAdd(name: String) {
        viewModelScope.launch {
            runCatching { playlistRepo.createPlaylist(name) }
                .onSuccess { addToPlaylist(it.id) }
                .onFailure {
                    _sheet.value = null
                    _notice.value = PlaylistNotice.Failed(it.message)
                }
        }
    }

    fun load() {
        _state.value = AlbumDetailUiState.Loading
        viewModelScope.launch {
            val serverUrl = repo.currentServerUrl()
            runCatching { repo.album(albumId) }
                .onSuccess { album ->
                    _state.value = AlbumDetailUiState.Loaded(serverUrl = serverUrl, album = album)
                }
                .onFailure { e ->
                    _state.value = AlbumDetailUiState.Error(e.message)
                }
        }
    }

    // Passes the albumId into the ViewModel, which the default factory can't do.
    class Factory(private val albumId: Int) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AlbumDetailViewModel(albumId) as T
    }
}
