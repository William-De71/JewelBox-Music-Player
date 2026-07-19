package com.jewelbox.player.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real DataStore on Robolectric: the URL saved from Settings (or picked from
 * mDNS discovery) must be exactly what every repository reads back, and the
 * server_id must ride along without disturbing URL-only writes.
 */
@RunWith(RobolectricTestRunner::class)
class ServerPrefsTest {

    private lateinit var context: Context
    private lateinit var prefs: ServerPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = ServerPrefs(context)
        // The preferencesDataStore delegate is a process-wide singleton bound to
        // the FIRST test's filesDir: deleting the current context's datastore
        // directory does not reach it, and values leak across tests. Reset
        // through the API itself so every test starts from known-blank values
        // regardless of execution order.
        runBlocking { prefs.setServer("", "") }
    }

    @Test
    fun `blank values read back as empty strings`() = runTest {
        assertEquals("", prefs.serverUrl.first())
        assertEquals("", prefs.serverId.first())
    }

    @Test
    fun `setServerUrl stores the trimmed url and leaves the id alone`() = runTest {
        prefs.setServer("http://old/", "id-1")
        prefs.setServerUrl("  http://192.168.1.20:3001/  ")

        assertEquals("http://192.168.1.20:3001/", prefs.serverUrl.first())
        // A manual URL change must not wipe the identity: the URL may still
        // point at the same server, and a wrong id only mislabels a badge.
        assertEquals("id-1", prefs.serverId.first())
    }

    @Test
    fun `setServer stores both halves together`() = runTest {
        prefs.setServer(" http://nas:3001/ ", " uuid-42 ")

        assertEquals("http://nas:3001/", prefs.serverUrl.first())
        assertEquals("uuid-42", prefs.serverId.first())
    }

    @Test
    fun `setServer overwrites a previous pairing`() = runTest {
        prefs.setServer("http://a/", "id-a")
        prefs.setServer("http://b/", "id-b")

        assertEquals("http://b/", prefs.serverUrl.first())
        assertEquals("id-b", prefs.serverId.first())
    }
}
