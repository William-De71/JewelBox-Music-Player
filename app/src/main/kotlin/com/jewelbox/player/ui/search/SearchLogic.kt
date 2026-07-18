package com.jewelbox.player.ui.search

/** Pure query rules, shared by the ViewModel and its unit tests. */
object SearchLogic {

    /** Server contract: GET /api/player/search rejects queries under 2 characters. */
    const val MIN_QUERY_LENGTH = 2

    /**
     * Trims the raw input and returns the text to send to the server, or null
     * when it is too short to search — the UI then stays on the idle prompt.
     */
    fun normalizeQuery(raw: String): String? =
        raw.trim().takeIf { it.length >= MIN_QUERY_LENGTH }
}
