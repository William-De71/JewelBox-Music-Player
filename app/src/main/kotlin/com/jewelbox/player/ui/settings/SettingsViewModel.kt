package com.jewelbox.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Result of pressing "Tester". */
sealed interface TestStatus {
    data object Idle : TestStatus
    data object Testing : TestStatus
    data object Connected : TestStatus

    /** The server answered but not with {"status":"ok"} — localized by the UI. */
    data object UnexpectedResponse : TestStatus

    /** Network/HTTP failure; detail may be null, the UI substitutes a localized fallback. */
    data class Failed(val detail: String?) : TestStatus
}

data class SettingsUiState(
    val url: String = "",
    val loaded: Boolean = false,
    val test: TestStatus = TestStatus.Idle,
    val saved: Boolean = false,
)

class SettingsViewModel : ViewModel() {

    private val prefs = ServiceLocator.serverPrefs
    private val repo = ServiceLocator.albumRepository

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = prefs.serverUrl.first()
            _state.value = _state.value.copy(url = stored, loaded = true)
        }
    }

    fun onUrlChange(value: String) {
        // Editing invalidates a previous test/save result.
        _state.value = _state.value.copy(url = value, test = TestStatus.Idle, saved = false)
    }

    fun test() {
        val raw = _state.value.url
        _state.value = _state.value.copy(test = TestStatus.Testing)
        viewModelScope.launch {
            val result = runCatching {
                val normalized = ApiClient.normalize(raw)
                repo.health(explicitUrl = normalized)
            }
            _state.value = _state.value.copy(
                test = result.fold(
                    onSuccess = { ok ->
                        if (ok) TestStatus.Connected else TestStatus.UnexpectedResponse
                    },
                    onFailure = { e -> TestStatus.Failed(e.message) },
                ),
            )
        }
    }

    fun save() {
        val raw = _state.value.url
        viewModelScope.launch {
            val normalized = runCatching { ApiClient.normalize(raw) }.getOrElse { raw.trim() }
            prefs.setServerUrl(normalized)
            _state.value = _state.value.copy(url = normalized, saved = true)
        }
    }
}
