package com.jewelbox.player.playback

/**
 * Soft transition between tracks, without overlap: the sound goes down over
 * the last seconds of a track and back up at the start of the next one —
 * never between two tracks that follow each other on the same album (a live
 * album stays gapless). Same rule as the PWA (client/src/utils/transitionFade.js).
 */
val TRANSITION_FADE_CHOICES = listOf(0, 2, 4, 6, 8)

/** Two queue entries that follow each other on their album (ids and positions known). */
fun followsOnAlbum(albumA: Int?, positionA: Int?, albumB: Int?, positionB: Int?): Boolean =
    albumA != null && albumA == albumB && positionA != null && positionB == positionA + 1

/** Volume multiplier at [positionMs] of a track of [durationMs]. */
fun transitionFadeFactor(
    positionMs: Long,
    durationMs: Long,
    fadeSec: Int,
    fadeIn: Boolean,
    fadeOut: Boolean,
): Float {
    if (fadeSec <= 0) return 1f
    val fadeMs = fadeSec * 1000f
    var factor = 1f
    if (fadeIn) factor = minOf(factor, positionMs.coerceAtLeast(0) / fadeMs)
    if (fadeOut && durationMs > 2 * fadeMs) {
        factor = minOf(factor, (durationMs - positionMs).coerceAtLeast(0) / fadeMs)
    }
    return factor.coerceIn(0f, 1f)
}
