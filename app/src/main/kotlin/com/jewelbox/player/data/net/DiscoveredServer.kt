package com.jewelbox.player.data.net

/**
 * A JewelBox server found on the LAN via mDNS (_jewelbox._tcp).
 *
 * Everything here is pure logic — TXT parsing, URL building, list aggregation —
 * kept apart from the NsdManager glue in [ServerDiscovery] so it can be unit
 * tested (and counted by the coverage gate) without an Android device.
 *
 * @property serviceName the mDNS instance name, e.g. "JewelBox (nas)". Unique
 *   on the network at a given moment: it is the aggregation key.
 * @property serverId the TXT "id" — same UUID as server-info's server_id. May
 *   be empty if the record was stripped; validation refetches it anyway.
 */
data class DiscoveredServer(
    val serviceName: String,
    val host: String,
    val port: Int,
    val version: String = "",
    val serverId: String = "",
) {
    /** Base URL ready for [ApiClient.create]; IPv6 literals need brackets. */
    val url: String
        get() {
            val h = if (host.contains(':')) "[$host]" else host
            return "http://$h:$port/"
        }

    companion object {
        /** TXT attributes arrive as raw bytes; absent or valueless keys map to "". */
        fun txtValue(attributes: Map<String, ByteArray?>, key: String): String =
            attributes[key]?.toString(Charsets.UTF_8).orEmpty()

        fun from(serviceName: String, host: String, port: Int, attributes: Map<String, ByteArray?>) =
            DiscoveredServer(
                serviceName = serviceName,
                host = host,
                port = port,
                version = txtValue(attributes, "version"),
                serverId = txtValue(attributes, "id"),
            )
    }
}

/** Adds or refreshes a server, keyed by service name; keeps a stable order. */
fun List<DiscoveredServer>.upsert(server: DiscoveredServer): List<DiscoveredServer> {
    val i = indexOfFirst { it.serviceName == server.serviceName }
    return if (i < 0) this + server
    else toMutableList().also { it[i] = server }
}

/** Drops a server when its mDNS record goes away (server stopped or left the LAN). */
fun List<DiscoveredServer>.without(serviceName: String): List<DiscoveredServer> =
    filterNot { it.serviceName == serviceName }
