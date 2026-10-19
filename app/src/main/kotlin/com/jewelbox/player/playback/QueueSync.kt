package com.jewelbox.player.playback

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Server-side queue sync: when to send what. Pure logic (unit-tested), driven
 * by [PlayerConnection]. The server keeps one queue per device (X-Device-Id
 * header), which is what lets another device — the desktop player, the PWA —
 * pick up what was playing here, and this one pick up theirs. Same protocol
 * as the PWA's PlayerContext and the desktop's core/queue_sync.py:
 *
 * - the tracks change (new album, added, removed…) → full PUT;
 * - only the current track changes → PATCH of the state;
 * - while playing, a PATCH of the position every [PROGRESS_INTERVAL_SEC];
 * - pausing → PATCH, to pin the exact position;
 * - queue emptied → DELETE.
 */
sealed interface SyncAction {
    data class Save(val trackIds: List<Int>, val currentIndex: Int, val positionSec: Double) : SyncAction
    data class State(val currentIndex: Int, val positionSec: Double) : SyncAction
    data object Clear : SyncAction
}

const val PROGRESS_INTERVAL_SEC = 30.0

class QueueSyncPlanner {

    private var trackIds: List<Int> = emptyList()
    private var index = -1
    private var playing = false
    private var lastPush = 0.0

    /** The next update re-sends the whole queue even if its tracks are unchanged (its source changed). */
    fun resend() {
        trackIds = emptyList()
    }

    /** New queue state; returns the call to make, or null. [nowSec] is any monotonic clock. */
    fun update(
        trackIds: List<Int>,
        currentIndex: Int?,
        positionSec: Double,
        isPlaying: Boolean,
        nowSec: Double,
    ): SyncAction? {
        val idx = currentIndex ?: -1
        val position = positionSec.coerceAtLeast(0.0)
        val wasPlaying = playing
        playing = isPlaying

        if (trackIds.isEmpty()) {
            val hadQueue = this.trackIds.isNotEmpty()
            this.trackIds = emptyList()
            index = -1
            return if (hadQueue) SyncAction.Clear else null
        }
        if (trackIds != this.trackIds) {
            this.trackIds = trackIds.toList()
            index = idx
            lastPush = nowSec
            return SyncAction.Save(this.trackIds, idx, position)
        }
        val pausedNow = wasPlaying && !playing
        val progressDue = playing && nowSec - lastPush >= PROGRESS_INTERVAL_SEC
        if (idx != index || pausedNow || progressDue) {
            index = idx
            lastPush = nowSec
            return SyncAction.State(idx, position)
        }
        return null
    }
}

/** Where to start a queue resumed from another device (see [resumeStart]). */
data class ResumeStart(val index: Int, val sameTrack: Boolean)

/**
 * Index to resume at once tracks without an audio file are dropped (playback
 * keeps only playable ones). [sameTrack] says whether the saved position
 * applies: false when the original current track isn't playable here (we
 * then start the next one from the top) or when the index is unknown.
 * Null when nothing is playable.
 */
fun resumeStart(playable: List<Boolean>, currentIndex: Int?): ResumeStart? {
    val total = playable.count { it }
    if (total == 0) return null
    if (currentIndex == null || currentIndex !in playable.indices) return ResumeStart(0, false)
    val before = playable.subList(0, currentIndex).count { it }
    if (playable[currentIndex]) return ResumeStart(before, true)
    return ResumeStart(minOf(before, total - 1), false)
}

/** How recent another device's queue must be to be offered on the home screen. */
const val RESUME_WINDOW_SEC = 24 * 3600L

private val SQLITE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** Parses a SQLite datetime('now') value (UTC, no zone) to epoch seconds, or null. */
fun parseServerTimestamp(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        LocalDateTime.parse(value.trim(), SQLITE_TIMESTAMP).toEpochSecond(ZoneOffset.UTC)
    } catch (e: DateTimeParseException) {
        null
    }
}

/**
 * The other device worth offering to resume: the most recently updated one
 * that still has tracks, if it was active within [RESUME_WINDOW_SEC]. Older
 * queues are noise — nobody wants last month's album pushed on the home screen.
 */
fun <T> pickResumable(
    devices: List<T>,
    trackCount: (T) -> Int,
    updatedAt: (T) -> String?,
    nowEpochSec: Long,
): T? = devices
    .filter { trackCount(it) > 0 }
    .mapNotNull { device -> parseServerTimestamp(updatedAt(device))?.let { device to it } }
    .filter { (_, at) -> nowEpochSec - at in 0..RESUME_WINDOW_SEC }
    .maxByOrNull { (_, at) -> at }
    ?.first

/**
 * Headline of the "resume from…" card: what the other device was on — its
 * current track ("Title — Artist"), else its queue's source, else null (the
 * card then falls back to the device name).
 */
fun resumeHeadline(currentTitle: String?, currentArtist: String?, sourceLabel: String?): String? = when {
    !currentTitle.isNullOrBlank() ->
        if (currentArtist.isNullOrBlank()) currentTitle else "$currentTitle — $currentArtist"
    !sourceLabel.isNullOrBlank() -> sourceLabel
    else -> null
}
