package com.jewelbox.player.data.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-device queue payloads (GET /api/player/queue/devices[/:id]): partial
 * rows from an older or emptied queue must parse with safe defaults.
 */
class QueueDtoParsingTest {

    // Same configuration as ApiClient's converter.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `parses a full device row`() {
        val devices = json.decodeFromString<QueueDevicesResponse>(
            """{"data":[{"device_id":"desk","device_label":"JewelBox bureau · pc",
                "current_index":3,"updated_at":"2026-10-17 20:00:00","track_count":12,"extra":1}]}""",
        )
        val device = devices.data.single()
        assertEquals("desk", device.deviceId)
        assertEquals("JewelBox bureau · pc", device.deviceLabel)
        assertEquals(3, device.currentIndex)
        assertEquals("2026-10-17 20:00:00", device.updatedAt)
        assertEquals(12, device.trackCount)
    }

    @Test
    fun `a device row says what was playing`() {
        val device = json.decodeFromString<QueueDeviceDto>(
            """{"device_id":"desk","source_label":"Mix dynamique",
                "current_track":{"id":4,"title":"Airbag","artist_name":"Radiohead",
                "album_title":"OK Computer","cover_url":"/covers/ok.jpg"}}""",
        )
        assertEquals("Mix dynamique", device.sourceLabel)
        val current = device.currentTrack!!
        assertEquals(4, current.id)
        assertEquals("Airbag", current.title)
        assertEquals("Radiohead", current.artistName)
        assertEquals("OK Computer", current.albumTitle)
        assertEquals("/covers/ok.jpg", current.coverUrl)
        val bare = json.decodeFromString<QueueCurrentTrackDto>("""{"id":1,"title":"x"}""")
        assertNull(bare.artistName)
        assertNull(bare.albumTitle)
        assertNull(bare.coverUrl)
    }

    @Test
    fun `a minimal device row falls back to defaults`() {
        val device = json.decodeFromString<QueueDeviceDto>("""{"device_id":"web","device_label":null}""")
        assertNull(device.deviceLabel)
        assertEquals(-1, device.currentIndex)
        assertNull(device.updatedAt)
        assertEquals(0, device.trackCount)
        assertNull(device.sourceLabel)
        assertNull(device.currentTrack)
        assertTrue(json.decodeFromString<QueueDevicesResponse>("{}").data.isEmpty())
    }

    @Test
    fun `parses a device queue with its position`() {
        val queue = json.decodeFromString<DeviceQueueDto>(
            """{"device_id":"phone","device_label":"Pixel","current_index":1,"position_sec":42.5,
                "updated_at":"2026-10-17 20:00:00",
                "tracks":[{"entry_id":1,"id":5,"title":"So What","has_file":true}]}""",
        )
        assertEquals("Pixel", queue.deviceLabel)
        assertEquals(1, queue.currentIndex)
        assertEquals(42.5, queue.positionSec, 0.0)
        assertEquals(5, queue.tracks.single().id)
        assertNull(queue.sourceLabel)
        assertEquals(
            "OK Computer",
            json.decodeFromString<DeviceQueueDto>("""{"device_id":"x","source_label":"OK Computer"}""").sourceLabel,
        )
    }

    @Test
    fun `an empty device queue falls back to defaults`() {
        val queue = json.decodeFromString<DeviceQueueDto>("""{"device_id":"x","position_sec":null}""")
        assertNull(queue.deviceLabel)
        assertEquals(-1, queue.currentIndex)
        assertEquals(0.0, queue.positionSec, 0.0)
        assertNull(queue.updatedAt)
        assertTrue(queue.tracks.isEmpty())
    }
}
