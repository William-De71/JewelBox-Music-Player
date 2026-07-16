package com.jewelbox.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Single DataStore for the app's small settings; today just the server URL.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jewelbox_settings")

/**
 * Persists the user-entered server base URL. This is the single source of truth
 * for "where is the server"; a future mDNS discovery feature would write here too.
 */
class ServerPrefs(private val context: Context) {

    val serverUrl: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[SERVER_URL_KEY].orEmpty() }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[SERVER_URL_KEY] = url.trim() }
    }

    private companion object {
        val SERVER_URL_KEY = stringPreferencesKey("server_url")
    }
}
