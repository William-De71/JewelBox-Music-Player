package com.jewelbox.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Single DataStore for the app's small settings; today just the server URL + id.
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

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[SERVER_URL_KEY] = url.trim() }
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
    }
}
