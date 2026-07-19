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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.HomeRecentItemDto

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object NoServer : HomeUiState

    /** The server predates 1.9 (no home endpoint), see SearchLogic.isServerTooOld. */
    data object ServerTooOld : HomeUiState
    data class Error(val message: String?) : HomeUiState
    data class Loaded(
        val serverUrl: String,
        val recent: List<HomeRecentItemDto>,
        val suggestions: List<AlbumDto>,
    ) : HomeUiState
}

class HomeViewModel : ViewModel() {

    private val repo = ServiceLocator.homeRepository

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        // React to the configured server URL and every later change (e.g. after
        // the user saves an address in Settings): (re)load automatically.
        repo.serverUrl
            .distinctUntilChanged()
            .onEach { url -> loadFor(url) }
            .launchIn(viewModelScope)
        // A new queue start means a new history entry server-side: refresh the
        // recent grid so it is up to date when the user comes back to this tab.
        PlayerConnection.queueSource
            .drop(1)
            .onEach { load() }
            .launchIn(viewModelScope)
    }

    /** Manual reload (used by the "Réessayer" button) against the current stored URL. */
    fun load() {
        viewModelScope.launch { loadFor(repo.currentServerUrl()) }
    }

    private suspend fun loadFor(serverUrl: String) {
        if (serverUrl.isBlank()) {
            _state.value = HomeUiState.NoServer
            return
        }
        if (_state.value !is HomeUiState.Loaded) {
            _state.value = HomeUiState.Loading
        }
        runCatching { repo.home() }
            .onSuccess { home ->
                _state.value = HomeUiState.Loaded(
                    serverUrl = serverUrl,
                    recent = home.recent,
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
