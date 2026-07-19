package com.jewelbox.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Kept in its own DataStore file: this is churny playback bookkeeping, written
// on every queue change, unlike the stable settings in ServerPrefs.
private val Context.playbackStore: DataStore<Preferences> by preferencesDataStore(name = "jewelbox_playback")

/** One queue entry, flat by design: everything a MediaItem needs to be rebuilt. */
@Serializable
data class SavedTrack(
    val id: Int,
    val title: String,
    @SerialName("artist_name") val artistName: String,
    @SerialName("album_title") val albumTitle: String,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
)

/**
 * The queue as it was when the app was last used, so playback can be restored
 * where the user left off. [sourceType]/[sourceId] mirror QueueSource so list
 * screens keep flagging the playing album or playlist after a restart.
 */
@Serializable
data class SavedQueue(
    @SerialName("server_url") val serverUrl: String,
    val tracks: List<SavedTrack>,
    val index: Int = 0,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("dynamic_mix") val dynamicMix: Boolean = false,
)

/** Persists the current queue so it survives the app being closed. */
class PlaybackStateStore(private val context: Context) {

    suspend fun save(queue: SavedQueue) {
        val encoded = runCatching { json.encodeToString(SavedQueue.serializer(), queue) }.getOrNull() ?: return
        context.playbackStore.edit { prefs -> prefs[QUEUE_KEY] = encoded }
    }

    /** Null when nothing was saved, or when the stored shape no longer parses. */
    suspend fun load(): SavedQueue? {
        val raw = runCatching { context.playbackStore.data.first() }.getOrNull()
            ?.get(QUEUE_KEY)
            ?: return null
        return runCatching { json.decodeFromString<SavedQueue>(raw) }.getOrNull()
    }

    suspend fun clear() {
        context.playbackStore.edit { prefs -> prefs.remove(QUEUE_KEY) }
    }

    private companion object {
        val QUEUE_KEY = stringPreferencesKey("saved_queue")
        val json = Json { ignoreUnknownKeys = true }
    }
}
