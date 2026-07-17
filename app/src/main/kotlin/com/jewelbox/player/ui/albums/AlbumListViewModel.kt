package com.jewelbox.player.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.AlbumDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

sealed interface AlbumsUiState {
    data object Loading : AlbumsUiState
    data object NoServer : AlbumsUiState
    data class Error(val message: String?) : AlbumsUiState
    data class Loaded(
        val serverUrl: String,
        val albums: List<AlbumDto>,
        val groupByArtist: Boolean,
        // Artist names whose group is currently collapsed (only meaningful when grouped).
        val collapsedArtists: Set<String>,
    ) : AlbumsUiState
}

// The whole owned collection is fetched in one call so grouping by artist is reliable
// (no artist split across pages). Large enough for a personal library.
private const val FETCH_LIMIT = 10000

class AlbumListViewModel : ViewModel() {

    private val repo = ServiceLocator.albumRepository

    private val _state = MutableStateFlow<AlbumsUiState>(AlbumsUiState.Loading)
    val state: StateFlow<AlbumsUiState> = _state.asStateFlow()

    // Survives reloads (URL re-emits) so the user's view preference sticks.
    private var groupByArtist = false

    init {
        // React to the configured server URL and every later change (e.g. after the
        // user saves an address in Settings): (re)load automatically, so the user
        // never has to restart the app to see the list appear.
        repo.serverUrl
            .distinctUntilChanged()
            .onEach { url -> loadFor(url) }
            .launchIn(viewModelScope)
    }

    /** Manual reload (used by the "Réessayer" button) against the current stored URL. */
    fun load() {
        viewModelScope.launch { loadFor(repo.currentServerUrl()) }
    }

    private suspend fun loadFor(serverUrl: String) {
        if (serverUrl.isBlank()) {
            _state.value = AlbumsUiState.NoServer
            return
        }
        _state.value = AlbumsUiState.Loading
        runCatching { repo.albums(page = 1, limit = FETCH_LIMIT) }
            .onSuccess { pageData ->
                _state.value = AlbumsUiState.Loaded(
                    serverUrl = serverUrl,
                    albums = pageData.data,
                    groupByArtist = groupByArtist,
                    // Start with every artist collapsed to minimise scrolling.
                    collapsedArtists = artistNames(pageData.data),
                )
            }
            .onFailure { e ->
                _state.value = AlbumsUiState.Error(e.message)
            }
    }

    /** Toggles the "group by artist" view mode. */
    fun setGroupByArtist(enabled: Boolean) {
        groupByArtist = enabled
        val current = _state.value as? AlbumsUiState.Loaded ?: return
        _state.value = current.copy(groupByArtist = enabled)
    }

    /** Collapses or expands one artist's group. */
    fun toggleArtist(artistName: String) {
        val current = _state.value as? AlbumsUiState.Loaded ?: return
        val next = current.collapsedArtists.toMutableSet().apply {
            if (!add(artistName)) remove(artistName)
        }
        _state.value = current.copy(collapsedArtists = next)
    }

    /** Collapses every artist group. */
    fun collapseAll() {
        val current = _state.value as? AlbumsUiState.Loaded ?: return
        _state.value = current.copy(collapsedArtists = artistNames(current.albums))
    }

    /** Expands every artist group. */
    fun expandAll() {
        val current = _state.value as? AlbumsUiState.Loaded ?: return
        _state.value = current.copy(collapsedArtists = emptySet())
    }

    private fun artistNames(albums: List<AlbumDto>): Set<String> =
        albums.mapTo(mutableSetOf()) { it.artist.name }
}
