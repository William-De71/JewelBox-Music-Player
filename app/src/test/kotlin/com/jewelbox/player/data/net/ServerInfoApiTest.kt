package com.jewelbox.player.data.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerInfoApiTest {

    @Test
    fun `server-info hits its endpoint and parses the identity`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"app":"jewelbox","name":"JewelBox (nas)","version":"1.12.0",
                     "server_id":"ff411a78-f9f3-4c41-9c8e-132e97b4ab52","api":"/api",
                     "collection":"william"}
                    """.trimIndent()
                )
            )
            server.start()

            val info = ApiClient.create(server.url("/").toString()).serverInfo()

            assertEquals("/api/server-info", server.takeRequest().path)
            assertEquals("jewelbox", info.app)
            assertEquals("JewelBox (nas)", info.name)
            assertEquals("1.12.0", info.version)
            assertEquals("ff411a78-f9f3-4c41-9c8e-132e97b4ab52", info.serverId)
            assertEquals("william", info.collection)
        }
    }

    @Test
    fun `a partial payload falls back to defaults instead of crashing`() {
        // ignoreUnknownKeys + defaults: a future server may add or drop fields.
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val info = json.decodeFromString<ServerInfoDto>("""{"app":"jewelbox","extra":1}""")

        assertEquals("jewelbox", info.app)
        assertEquals("", info.serverId)
        assertNull(info.collection)
    }
}
