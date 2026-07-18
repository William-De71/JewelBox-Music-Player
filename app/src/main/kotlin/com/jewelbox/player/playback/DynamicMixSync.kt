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

    fun plan(currentIds: List<Int>, serverTracks: List<QueueTrackDto>): QueueSyncPlan {
        val serverIds = serverTracks.mapTo(HashSet()) { it.id }
        val localIds = currentIds.toHashSet()
        return QueueSyncPlan(
            removeIds = currentIds.filter { it !in serverIds },
            append = serverTracks.filter { it.id !in localIds },
        )
    }
}
