package com.jewelbox.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

// Single DataStore for the app's small settings: server URL + id, device id.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jewelbox_settings")

/**
 * Persists the server base URL — the single source of truth for "where is the
 * server" — plus the server's stable identity.
 *
 * [serverId] is the UUID from GET /api/server-info: it survives DHCP address
 * changes, so mDNS discovery can flag "your" server in its results even when
 * its IP no longer matches the stored URL. It is best-effort metadata: an
 * empty value only means the badge is unavailable, never that the URL is bad.
 */
class ServerPrefs(private val context: Context) {

    val serverUrl: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[SERVER_URL_KEY].orEmpty() }

    val serverId: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[SERVER_ID_KEY].orEmpty() }

    /**
     * "Allow remote control": another client of the server (Gladys…) may
     * command this player. On by default, per install.
     */
    val remoteControl: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[REMOTE_CONTROL_KEY] ?: true }

    suspend fun setRemoteControl(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[REMOTE_CONTROL_KEY] = enabled }
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[SERVER_URL_KEY] = url.trim() }
    }

    /**
     * This install's stable identity, sent as X-Device-Id so the server keeps
     * one playback queue per device (cross-device resume). Minted on first use
     * and never regenerated: a new id would orphan the queue left behind.
     */
    suspend fun deviceId(): String {
        var id = ""
        context.dataStore.edit { prefs ->
            id = prefs[DEVICE_ID_KEY].orEmpty().ifBlank { UUID.randomUUID().toString() }
            prefs[DEVICE_ID_KEY] = id
        }
        return id
    }

    /** Saves both halves at once — the URL to reach the server, the id to recognize it. */
    suspend fun setServer(url: String, serverId: String) {
        context.dataStore.edit { prefs ->
            prefs[SERVER_URL_KEY] = url.trim()
            prefs[SERVER_ID_KEY] = serverId.trim()
        }
    }

    private companion object {
        val SERVER_URL_KEY = stringPreferencesKey("server_url")
        val SERVER_ID_KEY = stringPreferencesKey("server_id")
        val DEVICE_ID_KEY = stringPreferencesKey("device_id")
        val REMOTE_CONTROL_KEY = booleanPreferencesKey("remote_control")
    }
}
