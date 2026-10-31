package com.jewelbox.player.playback

import com.jewelbox.player.data.net.ApiClient
import com.jewelbox.player.data.net.RemoteDeviceDto
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The players seen by the server, live, for the remote screens: GET
 * /api/player/remote/watch sends the whole list, then one event per change
 * (see [applyWatchEvent]). Open only while collected; a dropped stream is
 * reopened like [RemoteControl]'s.
 */
object RemoteWatch {

    // The server writes a comment every 20 s: 60 s of silence is a dead connection.
    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Null until the first answer; an empty list when the stream cannot be opened (old server, unreachable). */
    fun devices(serverUrl: String): Flow<List<RemoteDeviceDto>?> = channelFlow {
        var devices: List<RemoteDeviceDto>? = null
        var attempt = 0
        while (isActive) {
            var opened = false
            events(serverUrl).collect { (event, data) ->
                opened = true
                devices = applyWatchEvent(devices.orEmpty(), event, data)
                send(devices)
            }
            if (opened) attempt = 0
            if (devices == null) {
                devices = emptyList()
                send(devices)
            }
            attempt++
            delay(reconnectDelaySec(attempt) * 1000)
        }
    }.conflate()

    /** One connection: its events until it ends (completes at once when it cannot open). */
    private fun events(serverUrl: String): Flow<Pair<String, JsonObject>> = callbackFlow {
        val url = runCatching { ApiClient.normalize(serverUrl) }.getOrNull()
            ?.let { "${it}api/player/remote/watch".toHttpUrlOrNull() }
        if (url == null) {
            close()
            return@callbackFlow
        }
        val call = okHttp.newCall(Request.Builder().url(url).header("Accept", "text/event-stream").build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val source = it.body?.source()
                    if (it.isSuccessful && source != null) {
                        val parser = SseParser()
                        try {
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                parser.line(line)?.let(::trySend)
                            }
                        } catch (e: IOException) {
                            // dropped connection or cancelled call: the caller reconnects (or stops)
                        }
                    }
                }
                close()
            }
        })
        awaitClose { call.cancel() }
    }
}
