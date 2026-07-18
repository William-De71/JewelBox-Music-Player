package com.jewelbox.player.ui.search

import kotlinx.serialization.SerializationException
import retrofit2.HttpException

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

    /**
     * True when the failure means the server predates 1.7 (no search endpoint).
     * Servers < 1.7 don't 404 on the unknown route: the SPA fallback answers
     * 200 with the PWA's index.html, so the JSON decoder throws instead —
     * both cases read as "update the server".
     */
    fun isServerTooOld(e: Throwable): Boolean =
        e is SerializationException || (e as? HttpException)?.code() == 404
}
