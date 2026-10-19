package com.jewelbox.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueSyncTest {

    // --- QueueSyncPlanner -------------------------------------------------------

    @Test
    fun `a new queue is saved whole`() {
        val planner = QueueSyncPlanner()
        assertEquals(
            SyncAction.Save(listOf(1, 2, 3), 1, 12.5),
            planner.update(listOf(1, 2, 3), 1, 12.5, isPlaying = true, nowSec = 0.0),
        )
    }

    @Test
    fun `nothing new sends nothing`() {
        val planner = QueueSyncPlanner()
        planner.update(listOf(1, 2), 0, 0.0, true, 0.0)
        assertNull(planner.update(listOf(1, 2), 0, 5.0, true, 5.0))
    }

    @Test
    fun `a track change sends the state`() {
        val planner = QueueSyncPlanner()
        planner.update(listOf(1, 2), 0, 0.0, true, 0.0)
        assertEquals(SyncAction.State(1, 0.4), planner.update(listOf(1, 2), 1, 0.4, true, 3.0))
    }

    @Test
    fun `progress is pinged periodically while playing`() {
        val planner = QueueSyncPlanner()
        planner.update(listOf(1), 0, 0.0, true, 0.0)
        assertNull(planner.update(listOf(1), 0, 10.0, true, PROGRESS_INTERVAL_SEC - 1))
        assertEquals(SyncAction.State(0, 31.0), planner.update(listOf(1), 0, 31.0, true, PROGRESS_INTERVAL_SEC + 1))
        assertNull(planner.update(listOf(1), 0, 32.0, true, PROGRESS_INTERVAL_SEC + 2))
    }

    @Test
    fun `no periodic ping while paused`() {
        val planner = QueueSyncPlanner()
        planner.update(listOf(1), 0, 0.0, false, 0.0)
        assertNull(planner.update(listOf(1), 0, 0.0, false, 10 * PROGRESS_INTERVAL_SEC))
    }

    @Test
    fun `pausing pins the position`() {
        val planner = QueueSyncPlanner()
        planner.update(listOf(1), 0, 0.0, true, 0.0)
        assertEquals(SyncAction.State(0, 7.25), planner.update(listOf(1), 0, 7.25, false, 4.0))
    }

    @Test
    fun `changed tracks are saved again`() {
        val planner = QueueSyncPlanner()
        planner.update(listOf(1, 2), 0, 0.0, true, 0.0)
        assertEquals(SyncAction.Save(listOf(1, 2, 9), 0, 3.0), planner.update(listOf(1, 2, 9), 0, 3.0, true, 1.0))
    }

    @Test
    fun `an emptied queue is cleared once`() {
        val planner = QueueSyncPlanner()
        planner.update(listOf(1), 0, 0.0, true, 0.0)
        assertEquals(SyncAction.Clear, planner.update(emptyList(), null, 0.0, false, 1.0))
        assertNull(planner.update(emptyList(), null, 0.0, false, 2.0))
    }

    @Test
    fun `empty from the start sends nothing`() {
        assertNull(QueueSyncPlanner().update(emptyList(), null, 0.0, false, 0.0))
    }

    @Test
    fun `unknown index and negative position are normalized`() {
        val planner = QueueSyncPlanner()
        planner.update(listOf(1), 0, 0.0, true, 0.0)
        assertEquals(SyncAction.State(-1, 0.0), planner.update(listOf(1), null, -3.0, false, 1.0))
    }

    // --- resumeStart ------------------------------------------------------------

    @Test
    fun `resume at the same playable track`() {
        assertEquals(ResumeStart(1, true), resumeStart(listOf(true, false, true, true), 2))
    }

    @Test
    fun `resume skips an unplayable current track`() {
        assertEquals(ResumeStart(1, false), resumeStart(listOf(true, false, true), 1))
    }

    @Test
    fun `an unplayable last track falls back to the last playable one`() {
        assertEquals(ResumeStart(1, false), resumeStart(listOf(true, true, false), 2))
    }

    @Test
    fun `an unknown index starts at the top`() {
        assertEquals(ResumeStart(0, false), resumeStart(listOf(true, true), -1))
        assertEquals(ResumeStart(0, false), resumeStart(listOf(true, true), 9))
        assertEquals(ResumeStart(0, false), resumeStart(listOf(true), null))
    }

    @Test
    fun `nothing playable gives null`() {
        assertNull(resumeStart(listOf(false, false), 0))
        assertNull(resumeStart(emptyList(), 0))
    }

    // --- parseServerTimestamp / pickResumable -----------------------------------

    @Test
    fun `server timestamps parse as UTC`() {
        assertEquals(1_700_000_000L, parseServerTimestamp("2023-11-14 22:13:20"))
        assertNull(parseServerTimestamp(null))
        assertNull(parseServerTimestamp(" "))
        assertNull(parseServerTimestamp("hier"))
    }

    private data class Dev(val name: String, val count: Int, val at: String?)

    private fun pick(devices: List<Dev>, now: Long) =
        pickResumable(devices, { it.count }, { it.at }, now)

    @Test
    fun `the most recent device with tracks is offered`() {
        val now = parseServerTimestamp("2026-10-17 20:00:00")!!
        val devices = listOf(
            Dev("old", 5, "2026-10-17 10:00:00"),
            Dev("recent", 3, "2026-10-17 19:30:00"),
            Dev("empty", 0, "2026-10-17 19:59:00"),
        )
        assertEquals("recent", pick(devices, now)?.name)
    }

    @Test
    fun `queues older than a day or without date are ignored`() {
        val now = parseServerTimestamp("2026-10-17 20:00:00")!!
        assertNull(pick(listOf(Dev("stale", 4, "2026-10-15 20:00:00"), Dev("undated", 4, null)), now))
    }

    @Test
    fun `a queue dated in the future is ignored`() {
        val now = parseServerTimestamp("2026-10-17 20:00:00")!!
        assertNull(pick(listOf(Dev("skewed", 4, "2026-10-18 20:00:00")), now))
    }

    @Test
    fun `resend forces a full save of unchanged tracks`() {
        val planner = QueueSyncPlanner()
        planner.update(listOf(1, 2), 0, 0.0, isPlaying = false, nowSec = 0.0)
        planner.resend()
        assertEquals(
            SyncAction.Save(listOf(1, 2), 1, 3.0),
            planner.update(listOf(1, 2), 1, 3.0, isPlaying = true, nowSec = 1.0),
        )
    }

    @Test
    fun `resume headline names the track, else the source`() {
        assertEquals("Airbag — Radiohead", resumeHeadline("Airbag", "Radiohead", "Mix dynamique"))
        assertEquals("Airbag", resumeHeadline("Airbag", null, null))
        assertEquals("Airbag", resumeHeadline("Airbag", " ", null))
        assertEquals("Mix dynamique", resumeHeadline(null, "Radiohead", "Mix dynamique"))
        assertEquals("Mix dynamique", resumeHeadline("", null, "Mix dynamique"))
        assertNull(resumeHeadline(null, null, null))
        assertNull(resumeHeadline(null, null, " "))
    }
}
