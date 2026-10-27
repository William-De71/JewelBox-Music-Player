package com.jewelbox.player.data.offline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The offline catalog and the pending plays, each in its own JSON file under
 * the app's files (not DataStore: a catalog can weigh a few hundred KB of
 * album details). Written atomically; an unreadable file reads as empty.
 */
class OfflineStore(context: Context) {

    private val dir = File(context.filesDir, "offline")
    private val catalogFile = File(dir, "catalog.json")
    private val playsFile = File(dir, "pending-plays.json")
    private val mutex = Mutex()

    private val _catalog = MutableStateFlow(read(catalogFile, OfflineCatalog.serializer()) ?: OfflineCatalog())
    val catalog: StateFlow<OfflineCatalog> = _catalog.asStateFlow()

    private val playsSerializer = ListSerializer(PendingPlay.serializer())
    private val _pendingPlays = MutableStateFlow(read(playsFile, playsSerializer) ?: emptyList())
    val pendingPlays: StateFlow<List<PendingPlay>> = _pendingPlays.asStateFlow()

    /** Folder of the covers kept for offline display. */
    val coversDir: File get() = File(dir, "covers")

    suspend fun updateCatalog(change: (OfflineCatalog) -> OfflineCatalog): OfflineCatalog = mutex.withLock {
        val next = change(_catalog.value)
        write(catalogFile, OfflineCatalog.serializer(), next)
        _catalog.value = next
        next
    }

    suspend fun updatePendingPlays(change: (List<PendingPlay>) -> List<PendingPlay>) = mutex.withLock {
        val next = change(_pendingPlays.value)
        write(playsFile, playsSerializer, next)
        _pendingPlays.value = next
    }

    private fun <T> read(file: File, serializer: KSerializer<T>): T? =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()

    private suspend fun <T> write(file: File, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, ".${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
