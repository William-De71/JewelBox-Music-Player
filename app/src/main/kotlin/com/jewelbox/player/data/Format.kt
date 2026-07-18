package com.jewelbox.player.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure formatting helpers for playlist metadata, mirroring the PWA's rendering
 * (client/src/pages/Playlists.jsx#formatSeconds/#fmtDate).
 */

/** 3723 → "1 h 02 min", 225 → "3 min", 0 or negative → "—". */
fun formatDurationSeconds(total: Int): String {
    if (total <= 0) return "—"
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "$h h ${m.toString().padStart(2, '0')} min" else "$m min"
}

private val FRENCH_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.FRENCH)

/** SQLite "2026-07-17 21:03:00" (or ISO date) → "17 juil. 2026"; unparseable → "—". */
fun formatUpdatedAt(dateTime: String?): String {
    val datePart = dateTime?.trim()?.take(10) ?: return "—"
    return runCatching { LocalDate.parse(datePart).format(FRENCH_DATE) }.getOrDefault("—")
}
