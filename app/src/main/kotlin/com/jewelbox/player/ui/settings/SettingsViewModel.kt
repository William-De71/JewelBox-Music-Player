package com.jewelbox.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.ApiClient
import com.jewelbox.player.data.net.DiscoveredServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    // mDNS discovery. `found` survives a stopped scan so results stay selectable;
    // a stale entry just fails validation cleanly when tapped.
    val discovering: Boolean = false,
    val discoveryFailed: Boolean = false,
    val found: List<DiscoveredServer> = emptyList(),
    /** server_id stored at last save — powers the "your server" badge. */
    val knownServerId: String = "",
    /** Service name being validated after a tap, for the row's spinner. */
    val validating: String? = null,
    /** Service name whose validation failed (not a JewelBox / unreachable). */
    val validationFailed: String? = null,
)

class SettingsViewModel : ViewModel() {

    private val prefs = ServiceLocator.serverPrefs
    private val repo = ServiceLocator.albumRepository
    private val discovery = ServiceLocator.serverDiscovery

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var discoveryJob: Job? = null

    init {
        viewModelScope.launch {
            val stored = prefs.serverUrl.first()
            val storedId = prefs.serverId.first()
            _state.value = _state.value.copy(url = stored, knownServerId = storedId, loaded = true)
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
            // Best-effort: capture the server's identity so discovery can flag
            // this server later even after its IP changes. Old servers (< 1.12)
            // don't have the endpoint — the URL alone still works fine.
            runCatching {
                val info = ApiClient.create(normalized).serverInfo()
                if (info.app == "jewelbox" && info.serverId.isNotBlank()) {
                    prefs.setServer(normalized, info.serverId)
                    _state.value = _state.value.copy(knownServerId = info.serverId)
                }
            }
        }
    }

    /** Starts or stops the mDNS browse; browsing also stops when leaving the screen. */
    fun toggleDiscovery() {
        val job = discoveryJob
        if (job != null) {
            job.cancel()
            discoveryJob = null
            _state.value = _state.value.copy(discovering = false)
            return
        }
        _state.value = _state.value.copy(discovering = true, discoveryFailed = false)
        discoveryJob = discovery.discover()
            .onEach { servers -> _state.value = _state.value.copy(found = servers) }
            .catch {
                _state.value = _state.value.copy(discovering = false, discoveryFailed = true)
                discoveryJob = null
            }
            .launchIn(viewModelScope)
    }

    /**
     * A discovered server was tapped: confirm over HTTP that it really is a
     * JewelBox (mDNS says where something lives, server-info says what it is),
     * then persist URL + server_id together.
     */
    fun selectServer(server: DiscoveredServer) {
        _state.value = _state.value.copy(validating = server.serviceName, validationFailed = null)
        viewModelScope.launch {
            val result = runCatching { ApiClient.create(server.url).serverInfo() }
            val info = result.getOrNull()
            if (info != null && info.app == "jewelbox") {
                prefs.setServer(server.url, info.serverId)
                _state.value = _state.value.copy(
                    url = server.url,
                    knownServerId = info.serverId,
                    saved = true,
                    test = TestStatus.Idle,
                    validating = null,
                )
            } else {
                _state.value = _state.value.copy(
                    validating = null,
                    validationFailed = server.serviceName,
                )
            }
        }
    }
}
