package com.jewelbox.player.playback

import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Keeps the remote-control stream open while the setting allows it and a
 * server is configured (see [RemoteLogic.kt] for the protocol). Commands are
 * handed to [PlayerConnection] on the main thread. Best-effort like the queue
 * sync: an old server (404) or an unreachable one only means fewer and fewer
 * reconnection attempts.
 *
 * The stream lives as long as the app process: while the playback service
 * plays, and while the app is open. A killed app cannot be commanded.
 */
object RemoteControl {

    // The server writes a comment every 20 s: 60 s of silence means a dead
    // connection (Wi-Fi lost, phone asleep), reopened from scratch.
    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun start(scope: CoroutineScope, onOpened: () -> Unit, onCommand: (JsonObject) -> Unit) {
        val prefs = ServiceLocator.serverPrefs
        scope.launch {
            combine(prefs.serverUrl, prefs.remoteControl) { url, on -> url.takeIf { on && it.isNotBlank() } }
                .distinctUntilChanged()
                .collectLatest { url ->
                    if (url == null) return@collectLatest
                    val deviceId = prefs.deviceId()
                    var attempt = 0
                    while (isActive) {
                        if (listen(url, deviceId, scope, onOpened, onCommand)) attempt = 0
                        attempt++
                        delay(reconnectDelaySec(attempt) * 1000)
                    }
                }
        }
    }

    /** Reads the stream until it ends; true when it was opened. Cancelling the caller cancels the call. */
    private suspend fun listen(
        serverUrl: String,
        deviceId: String,
        scope: CoroutineScope,
        onOpened: () -> Unit,
        onCommand: (JsonObject) -> Unit,
    ): Boolean {
        val url = runCatching { ApiClient.normalize(serverUrl) }.getOrNull()
            ?.let { "${it}api/player/remote/events".toHttpUrlOrNull() }
            ?.newBuilder()
            ?.addQueryParameter("label", PlayerConnection.deviceLabel)
            ?.addQueryParameter("capabilities", REMOTE_CAPABILITIES.joinToString(","))
            ?.build()
            ?: return false
        val request = Request.Builder()
            .url(url)
            .header(ApiClient.DEVICE_ID_HEADER, deviceId)
            .header("Accept", "text/event-stream")
            .build()
        return suspendCancellableCoroutine { cont ->
            val call = okHttp.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            if (cont.isActive) cont.resume(false)
                            return
                        }
                        val source = it.body?.source()
                        if (source == null) {
                            if (cont.isActive) cont.resume(false)
                            return
                        }
                        scope.launch(Dispatchers.Main) { onOpened() }
                        val parser = SseParser()
                        try {
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                val (event, data) = parser.line(line) ?: continue
                                if (event == "command") scope.launch(Dispatchers.Main) { onCommand(data) }
                            }
                        } catch (e: IOException) {
                            // dropped connection or cancelled call: reconnect (or stop) below
                        }
                    }
                    if (cont.isActive) cont.resume(true)
                }
            })
        }
    }
}
