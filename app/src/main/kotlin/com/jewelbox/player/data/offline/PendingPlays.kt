package com.jewelbox.player.data.offline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A play the server could not be told about (offline, server down): kept on
 * the phone and sent on its return, with its real date — the play counter and
 * the listening statistics take `played_at` (server >= 1.20, older ones count
 * it now), Last.fm takes the start of the track, up to [SCROBBLE_MAX_AGE_SEC].
 */
@Serializable
data class PendingPlay(
    @SerialName("track_id") val trackId: Int,
    /** Epoch seconds the track started (the scrobble timestamp). */
    @SerialName("started_at") val startedAt: Long,
    /** Epoch milliseconds the play was counted. */
    @SerialName("played_at_ms") val playedAtMs: Long,
    /** The counter already has it: only the scrobble is left. */
    val counted: Boolean = false,
)

/** Last.fm refuses a scrobble older than 14 days: one day of margin. */
const val SCROBBLE_MAX_AGE_SEC = 13L * 24 * 3600

/** A long offline trip is bounded: the oldest plays go first. */
const val PENDING_PLAYS_MAX = 2000

fun PendingPlay.playedAtIso(): String = Instant.ofEpochMilli(playedAtMs).toString()

fun PendingPlay.scrobbleExpired(nowSec: Long): Boolean = nowSec - startedAt > SCROBBLE_MAX_AGE_SEC

fun enqueuePlay(queue: List<PendingPlay>, play: PendingPlay): List<PendingPlay> =
    (queue + play).takeLast(PENDING_PLAYS_MAX)
