package com.jewelbox.player.data.net

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Browses the LAN for JewelBox servers (mDNS type _jewelbox._tcp, matching
 * SERVICE_TYPE in the server's utils/mdns.js) and emits the up-to-date list as
 * services appear, refresh and vanish.
 *
 * Thin NsdManager glue, excluded from the coverage gate like the playback
 * service: it cannot run without a device. Everything decidable — TXT parsing,
 * URL building, list aggregation — lives in DiscoveredServer.kt and is tested.
 *
 * Two NsdManager quirks shape this code:
 *  - resolveService rejects concurrent calls (FAILURE_ALREADY_ACTIVE), so
 *    resolutions are serialized through a single consumer coroutine;
 *  - listener callbacks land on a binder thread, so that same single consumer
 *    is also the only place the list is mutated.
 */
class ServerDiscovery(private val nsdManager: NsdManager) {

    private sealed interface Event {
        data class Found(val info: NsdServiceInfo) : Event
        data class Lost(val serviceName: String) : Event
    }

    /**
     * Cold flow: collection starts the mDNS browse, cancellation stops it.
     * Emits the full current list on every change, starting with an empty one.
     * Fails with [IllegalStateException] if the browse cannot start at all.
     */
    fun discover(): Flow<List<DiscoveredServer>> = callbackFlow {
        val events = Channel<Event>(Channel.UNLIMITED)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                events.trySend(Event.Found(info))
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                events.trySend(Event.Lost(info.serviceName))
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("NSD discovery failed to start (error $errorCode)"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        // Single consumer: owns the list, serializes resolutions.
        val consumer = launch {
            var servers = emptyList<DiscoveredServer>()
            for (event in events) {
                servers = when (event) {
                    is Event.Found -> resolve(event.info)?.let { servers.upsert(it) } ?: servers
                    is Event.Lost -> servers.without(event.serviceName)
                }
                trySend(servers)
            }
        }

        trySend(emptyList())
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        awaitClose {
            consumer.cancel()
            // Throws if the listener never registered (start failed): already closed.
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
    }

    /** One resolution at a time; a failed resolve just drops the service. */
    @Suppress("DEPRECATION") // resolveService: the API 34 replacement would raise minSdk
    private suspend fun resolve(info: NsdServiceInfo): DiscoveredServer? =
        suspendCancellableCoroutine { cont ->
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    if (cont.isActive) {
                        cont.resume(
                            host?.let {
                                DiscoveredServer.from(
                                    serviceName = serviceInfo.serviceName,
                                    host = it,
                                    port = serviceInfo.port,
                                    attributes = serviceInfo.attributes,
                                )
                            },
                        )
                    }
                }
            })
        }

    companion object {
        const val SERVICE_TYPE = "_jewelbox._tcp"
    }
}
