package com.jewelbox.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.QueueTrackDto
import com.jewelbox.player.ui.playlists.PlaylistNotice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    /** Query empty or too short: show the prompt, no network call. */
    data object Idle : SearchUiState
    data object NoServer : SearchUiState
    data object Loading : SearchUiState
    /** [serverTooOld]: the server predates 1.7, see SearchLogic.isServerTooOld. */
    data class Error(val message: String?, val serverTooOld: Boolean = false) : SearchUiState
    data class Loaded(
        val serverUrl: String,
        val albums: List<AlbumDto>,
        val tracks: List<QueueTrackDto>,
    ) : SearchUiState
}

private const val DEBOUNCE_MS = 300L

class SearchViewModel : ViewModel() {

    private val albumRepo = ServiceLocator.albumRepository
    private val playlistRepo = ServiceLocator.playlistRepository

    /** Raw text of the search field; kept here so tab switches preserve it. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<PlaylistNotice?>(null)
    val notice: StateFlow<PlaylistNotice?> = _notice.asStateFlow()
    fun consumeNotice() { _notice.value = null }

    private var searchJob: Job? = null

    fun onQueryChange(raw: String) {
        _query.value = raw
        searchJob?.cancel()
        val normalized = SearchLogic.normalizeQuery(raw)
        if (normalized == null) {
            _state.value = SearchUiState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            search(normalized)
        }
    }

    /** Immediate re-run of the current query (IME Search action, "Réessayer"). */
    fun searchNow() {
        searchJob?.cancel()
        val normalized = SearchLogic.normalizeQuery(_query.value) ?: return
        searchJob = viewModelScope.launch { search(normalized) }
    }

    private suspend fun search(query: String) {
        val serverUrl = albumRepo.currentServerUrl()
        if (serverUrl.isBlank()) {
            _state.value = SearchUiState.NoServer
            return
        }
        _state.value = SearchUiState.Loading
        runCatching { albumRepo.search(query) }
            .onSuccess {
                _state.value = SearchUiState.Loaded(serverUrl, it.albums, it.tracks)
            }
            .onFailure { e ->
                _state.value = SearchUiState.Error(
                    message = e.message,
                    serverTooOld = SearchLogic.isServerTooOld(e),
                )
            }
    }

    /** Optimistic favorite flip on a track result, rolled back if the server refuses. */
    fun toggleFavorite(trackId: Int) {
        val before = _state.value as? SearchUiState.Loaded ?: return
        val next = !(before.tracks.firstOrNull { it.id == trackId }?.isFavorite ?: return)
        fun apply(state: SearchUiState.Loaded, value: Boolean) = state.copy(
            tracks = state.tracks.map {
                if (it.id == trackId) it.copy(isFavorite = value) else it
            },
        )
        _state.value = apply(before, next)
        viewModelScope.launch {
            runCatching { playlistRepo.setFavorite(trackId, next) }
                .onFailure {
                    (_state.value as? SearchUiState.Loaded)
                        ?.let { _state.value = apply(it, !next) }
                    _notice.value = PlaylistNotice.Failed(it.message)
                }
        }
    }
}
