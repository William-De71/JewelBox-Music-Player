package com.jewelbox.player.ui.albumdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.AlbumDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AlbumDetailUiState {
    data object Loading : AlbumDetailUiState
    data class Error(val message: String?) : AlbumDetailUiState
    data class Loaded(val serverUrl: String, val album: AlbumDto) : AlbumDetailUiState
}

class AlbumDetailViewModel(private val albumId: Int) : ViewModel() {

    private val repo = ServiceLocator.albumRepository

    private val _state = MutableStateFlow<AlbumDetailUiState>(AlbumDetailUiState.Loading)
    val state: StateFlow<AlbumDetailUiState> = _state.asStateFlow()

    init {
        load()
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
