package com.jewelbox.player.playback

import com.jewelbox.player.data.net.QueueTrackDto

/**
 * Reconciliation plan between the local player queue and the dynamic mix list
 * returned by the server after a track was consumed.
 */
data class QueueSyncPlan(
    /** Track ids present locally but no longer in the server list (played/dropped). */
    val removeIds: List<Int>,
    /** Server tracks missing locally, to append at the end in server order. */
    val append: List<QueueTrackDto>,
)

/**
 * Pure diff logic for the dynamic mix (kept player-free so it is unit-testable,
 * like ScrobbleTracker). The server only ever removes consumed/unplayable tracks
 * and refills at the bottom of the list, so bringing the local queue up to date
 * is exactly: drop what disappeared, append what is new — the relative order of
 * the surviving tracks never changes.
 */
object DynamicMixSync {

    /** Tracks kept behind the current one ("previous" goes back to them) before they are consumed. */
    const val KEEP_BEHIND = 4

    /**
     * Tracks to consume when skipping through the mix by hand. A skipped track
     * is not consumed (only one played through is), so skipping alone would run
     * the queue dry without the server ever refilling it. Reaching track
     * [KEEP_BEHIND] + 1 therefore consumes the ones before it: the current track
     * is back on top and the bottom of the list refills. [playedBefore] are the
     * tracks before the current one, in play order. Same rule as the PWA
     * (MIX_KEEP_BEHIND) and the desktop (core/dynamic_mix.py).
     */
    fun trimIds(playedBefore: List<Int>): List<Int> =
        if (playedBefore.size >= KEEP_BEHIND) playedBefore else emptyList()

    fun plan(currentIds: List<Int>, serverTracks: List<QueueTrackDto>): QueueSyncPlan {
        val serverIds = serverTracks.mapTo(HashSet()) { it.id }
        val localIds = currentIds.toHashSet()
        return QueueSyncPlan(
            removeIds = currentIds.filter { it !in serverIds },
            append = serverTracks.filter { it.id !in localIds },
        )
    }
}
