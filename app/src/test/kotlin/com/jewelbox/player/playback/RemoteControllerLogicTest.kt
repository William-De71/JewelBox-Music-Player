package com.jewelbox.player.playback

import com.jewelbox.player.data.net.RemoteDeviceDto
import com.jewelbox.player.data.net.RemoteStateDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RemoteControllerLogicTest {

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    // --- applyWatchEvent --------------------------------------------------------

    @Test
    fun `the list, then one device added, changed and gone`() {
        var devices = applyWatchEvent(
            emptyList(), "devices",
            obj("""{"data":[{"device_id":"desk","device_label":"JewelBox bureau · fedora","controllable":true,
                "capabilities":["seek","volume"],"state":{"playback_state":"playing","position_sec":12.5,
                "track":{"id":7,"title":"Leave Me Out","artist_name":"Silverchair"},"extra":1}}]}"""),
        )
        assertEquals(1, devices.size)
        assertEquals("Leave Me Out", devices[0].state?.track?.title)
        assertEquals(listOf("seek", "volume"), devices[0].capabilities)

        devices = applyWatchEvent(devices, "device", obj("""{"device_id":"web","controllable":false}"""))
        assertEquals(listOf("desk", "web"), devices.map { it.deviceId })

        devices = applyWatchEvent(devices, "device", obj("""{"device_id":"desk","controllable":true,"state":{"playback_state":"paused"}}"""))
        assertEquals(listOf("desk", "web"), devices.map { it.deviceId })
        assertEquals("paused", devices[0].state?.playbackState)

        devices = applyWatchEvent(devices, "gone", obj("""{"device_id":"web"}"""))
        assertEquals(listOf("desk"), devices.map { it.deviceId })
    }

    @Test
    fun `unknown events and bad data leave the list as it is`() {
        val devices = listOf(RemoteDeviceDto(deviceId = "desk"))
        assertEquals(devices, applyWatchEvent(devices, "hello", obj("""{"x":1}""")))
        assertEquals(devices, applyWatchEvent(devices, "device", obj("""{"no_id":true}""")))
    }

    @Test
    fun `only the other controllable players can be driven`() {
        val devices = listOf(
            RemoteDeviceDto(deviceId = "me", controllable = true),
            RemoteDeviceDto(deviceId = "desk", controllable = true),
            RemoteDeviceDto(deviceId = "web", controllable = false),
        )
        assertEquals(listOf("desk"), controllableOthers(devices, "me").map { it.deviceId })
    }

    // --- livePositionSec --------------------------------------------------------

    @Test
    fun `the position moves on while playing, up to the end`() {
        val at = Instant.parse("2026-10-31T13:34:22.588Z").toEpochMilli()
        val playing = RemoteStateDto(playbackState = "playing", positionSec = 60.0, durationSec = 183.3, reportedAt = "2026-10-31T13:34:22.588Z")
        assertEquals(70.0, livePositionSec(playing, at + 10_000), 0.001)
        assertEquals(183.3, livePositionSec(playing, at + 600_000), 0.001)
        // A phone clock behind the server's: never backwards.
        assertEquals(60.0, livePositionSec(playing, at - 5_000), 0.001)
        assertEquals(60.0, livePositionSec(playing.copy(playbackState = "paused"), at + 10_000), 0.001)
        assertEquals(60.0, livePositionSec(playing.copy(reportedAt = "yesterday"), at + 10_000), 0.001)
    }

    // --- commands ---------------------------------------------------------------

    @Test
    fun `repeat cycles off, all, one`() {
        assertEquals("all", nextRepeatMode("off"))
        assertEquals("one", nextRepeatMode("all"))
        assertEquals("off", nextRepeatMode("one"))
        assertEquals("all", nextRepeatMode(null))
    }

    @Test
    fun `command bodies match the server's validation`() {
        assertEquals("""{"command":"toggle"}""", RemoteCommands.simple("toggle").toString())
        assertEquals("""{"command":"seek","position_sec":0.0}""", RemoteCommands.seek(-3.0).toString())
        assertEquals("""{"command":"volume","volume":100}""", RemoteCommands.volume(140).toString())
        assertEquals("""{"command":"mute","muted":true}""", RemoteCommands.mute(true).toString())
        assertEquals("""{"command":"shuffle","shuffle":false}""", RemoteCommands.shuffle(false).toString())
        assertEquals("""{"command":"repeat","repeat":"one"}""", RemoteCommands.repeat("one").toString())
        assertEquals("""{"command":"play_index","index":4}""", RemoteCommands.playIndex(4).toString())
        assertEquals(
            """{"command":"play_media","mode":"replace","media":{"type":"album","id":587}}""",
            RemoteCommands.playMedia(RemoteMedia.Album(587)).toString(),
        )
        assertEquals(
            """{"command":"play_media","mode":"replace","media":{"type":"smart","id":"dynamic_mix"}}""",
            RemoteCommands.playMedia(RemoteMedia.Smart("dynamic_mix")).toString(),
        )
        assertTrue(RemoteCommands.playMedia(RemoteMedia.Playlist(3)).toString().contains(""""type":"playlist","id":3"""))
    }
}
