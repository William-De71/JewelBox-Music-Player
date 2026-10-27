package com.jewelbox.player.playback

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLogicTest {

    // --- SseParser --------------------------------------------------------------

    @Test
    fun `an event is complete at its blank line`() {
        val parser = SseParser()
        assertNull(parser.line("retry: 3000"))
        assertNull(parser.line(""))
        assertNull(parser.line("event: command"))
        assertNull(parser.line("""data: {"command":"seek","position_sec":42}"""))
        val (event, data) = parser.line("")!!
        assertEquals("command", event)
        assertEquals(42, data["position_sec"]!!.jsonPrimitive.int)
    }

    @Test
    fun `comments and bad data are ignored`() {
        val parser = SseParser()
        assertNull(parser.line(": ping"))
        assertNull(parser.line(""))
        parser.line("event: command")
        parser.line("data: not json")
        assertNull(parser.line(""))
        parser.line("data: {\"a\": 1}\r")
        assertEquals("message", parser.line("\r")!!.first)
    }

    // --- liveStateOf ------------------------------------------------------------

    private fun live(
        isPlaying: Boolean = true,
        positionMs: Long = 10_000,
        volume: Float = 1f,
        trackId: Int = 7,
        repeat: RepeatMode = RepeatMode.OFF,
    ) = liveStateOf(
        hasItem = true, isPlaying = isPlaying, trackId = trackId, currentIndex = 2,
        positionMs = positionMs, durationMs = 200_000, volume = volume,
        shuffle = false, repeat = repeat, deviceLabel = "Pixel",
    )

    @Test
    fun `the live state of a playing player`() {
        assertEquals(
            LiveState("playing", 7, 2, 12.3, 200.0, 50, false, "all", "Pixel", null),
            liveStateOf(true, true, 7, 2, 12_345, 200_000, 0.5f, false, RepeatMode.ALL, "Pixel"),
        )
    }

    @Test
    fun `an empty player is idle`() {
        val state = liveStateOf(false, false, null, 0, 0, 0, 1f, false, RepeatMode.OFF, "Pixel")
        assertEquals("idle", state.playbackState)
        assertEquals(-1, state.currentIndex)
        assertNull(state.trackId)
    }

    @Test
    fun `every field is serialized, defaults included`() {
        val json = Json.encodeToString(live())
        for (field in listOf("\"shuffle\":false", "\"repeat\":\"off\"", "\"volume\":100", "\"current_index\":2")) {
            assertTrue(json, json.contains(field))
        }
    }

    // --- LiveReporter -----------------------------------------------------------

    @Test
    fun `the first state is reported, playing on is not`() {
        val reporter = LiveReporter()
        assertTrue(reporter.update(live(positionMs = 10_000), 0.0))
        assertFalse(reporter.update(live(positionMs = 11_000), 1.0))
        assertFalse(reporter.update(live(positionMs = 15_000), 5.0))
    }

    @Test
    fun `a change the controller shows is reported`() {
        val reporter = LiveReporter()
        reporter.update(live(), 0.0)
        assertTrue(reporter.update(live(isPlaying = false), 1.0))
        assertTrue(reporter.update(live(isPlaying = false, volume = 0.3f), 2.0))
        assertTrue(reporter.update(live(isPlaying = false, volume = 0.3f, repeat = RepeatMode.ONE), 3.0))
        assertFalse(reporter.update(live(isPlaying = false, volume = 0.3f, repeat = RepeatMode.ONE), 4.0))
    }

    @Test
    fun `a seek is reported, playing or paused`() {
        val reporter = LiveReporter()
        reporter.update(live(positionMs = 10_000), 0.0)
        assertTrue(reporter.update(live(positionMs = 60_000), 1.0))
        reporter.update(live(isPlaying = false, positionMs = 60_000), 2.0)
        assertFalse(reporter.update(live(isPlaying = false, positionMs = 60_000), 50.0))
        assertTrue(reporter.update(live(isPlaying = false, positionMs = 90_000), 51.0))
    }

    @Test
    fun `a heartbeat while playing, and again after forget`() {
        val reporter = LiveReporter()
        reporter.update(live(positionMs = 0), 0.0)
        assertFalse(reporter.update(live(positionMs = 29_000), 29.0))
        assertTrue(reporter.update(live(positionMs = 30_000), LIVE_HEARTBEAT_SEC))
        reporter.forget()
        assertTrue(reporter.update(live(positionMs = 30_000), LIVE_HEARTBEAT_SEC))
    }

    @Test
    fun `reconnection delays grow and cap`() {
        assertEquals(listOf(2L, 4L, 8L, 60L, 60L), listOf(1, 2, 3, 6, 20).map(::reconnectDelaySec))
        assertEquals(2L, reconnectDelaySec(0))
    }
}
