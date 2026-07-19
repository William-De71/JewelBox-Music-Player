package com.jewelbox.player.data.net

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveredServerTest {

    private fun txt(vararg pairs: Pair<String, String?>): Map<String, ByteArray?> =
        pairs.toMap().mapValues { it.value?.toByteArray(Charsets.UTF_8) }

    // --- TXT parsing ---------------------------------------------------------

    @Test
    fun `from reads version and id out of the txt records`() {
        val server = DiscoveredServer.from(
            serviceName = "JewelBox (nas)",
            host = "192.168.1.20",
            port = 3001,
            attributes = txt("app" to "jewelbox", "version" to "1.12.0", "id" to "uuid-1"),
        )
        assertEquals("JewelBox (nas)", server.serviceName)
        assertEquals("1.12.0", server.version)
        assertEquals("uuid-1", server.serverId)
    }

    @Test
    fun `missing or valueless txt keys map to empty strings`() {
        // Some mDNS stacks strip TXT records or send keys without a value.
        val server = DiscoveredServer.from("s", "h", 1, txt("id" to null))
        assertEquals("", server.version)
        assertEquals("", server.serverId)
    }

    // --- URL building --------------------------------------------------------

    @Test
    fun `url wraps an ipv4 host plainly`() {
        assertEquals(
            "http://192.168.1.20:3001/",
            DiscoveredServer("s", "192.168.1.20", 3001).url,
        )
    }

    @Test
    fun `url brackets an ipv6 literal`() {
        // NsdManager may resolve to a link-local IPv6; unbracketed it is not a valid URL.
        assertEquals(
            "http://[fe80::1]:3001/",
            DiscoveredServer("s", "fe80::1", 3001).url,
        )
    }

    // --- List aggregation ----------------------------------------------------

    private val a = DiscoveredServer("A", "10.0.0.1", 3001)
    private val b = DiscoveredServer("B", "10.0.0.2", 3001)

    @Test
    fun `upsert appends a new server`() {
        assertEquals(listOf(a, b), listOf(a).upsert(b))
    }

    @Test
    fun `upsert refreshes in place keeping the order`() {
        // The same instance re-announced with a new address (DHCP renewal).
        val moved = a.copy(host = "10.0.0.9")
        assertEquals(listOf(moved, b), listOf(a, b).upsert(moved))
    }

    @Test
    fun `without drops the lost service and nothing else`() {
        assertEquals(listOf(b), listOf(a, b).without("A"))
        assertEquals(listOf(a, b), listOf(a, b).without("unknown"))
    }
}
