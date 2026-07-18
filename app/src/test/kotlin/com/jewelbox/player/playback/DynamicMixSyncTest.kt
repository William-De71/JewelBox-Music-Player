package com.jewelbox.player.playback

import com.jewelbox.player.data.net.QueueTrackDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicMixSyncTest {

    private fun track(id: Int) = QueueTrackDto(id = id, title = "T$id", hasFile = true)

    @Test
    fun `played track is removed and refill is appended`() {
        // Server consumed track 1 and refilled with 4 at the bottom.
        val plan = DynamicMixSync.plan(
            currentIds = listOf(1, 2, 3),
            serverTracks = listOf(track(2), track(3), track(4)),
        )

        assertEquals(listOf(1), plan.removeIds)
        assertEquals(listOf(4), plan.append.map { it.id })
    }

    @Test
    fun `identical lists need no change`() {
        val plan = DynamicMixSync.plan(
            currentIds = listOf(1, 2, 3),
            serverTracks = listOf(track(1), track(2), track(3)),
        )

        assertTrue(plan.removeIds.isEmpty())
        assertTrue(plan.append.isEmpty())
    }

    @Test
    fun `appended tracks keep the server order`() {
        val plan = DynamicMixSync.plan(
            currentIds = listOf(5),
            serverTracks = listOf(track(5), track(9), track(7), track(8)),
        )

        assertEquals(listOf(9, 7, 8), plan.append.map { it.id })
    }

    @Test
    fun `empty local queue appends the whole server list`() {
        val plan = DynamicMixSync.plan(
            currentIds = emptyList(),
            serverTracks = listOf(track(1), track(2)),
        )

        assertTrue(plan.removeIds.isEmpty())
        assertEquals(listOf(1, 2), plan.append.map { it.id })
    }

    @Test
    fun `empty server list drops everything local`() {
        val plan = DynamicMixSync.plan(
            currentIds = listOf(1, 2),
            serverTracks = emptyList(),
        )

        assertEquals(listOf(1, 2), plan.removeIds)
        assertTrue(plan.append.isEmpty())
    }

    @Test
    fun `several tracks can disappear at once`() {
        // The server also drops tracks whose audio file vanished.
        val plan = DynamicMixSync.plan(
            currentIds = listOf(1, 2, 3, 4),
            serverTracks = listOf(track(2), track(4)),
        )

        assertEquals(listOf(1, 3), plan.removeIds)
        assertTrue(plan.append.isEmpty())
    }
}
