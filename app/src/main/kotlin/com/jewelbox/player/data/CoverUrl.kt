package com.jewelbox.player.data

/**
 * The server stores cover_url either as an absolute URL (coverartarchive.org,
 * Discogs) or as a relative path "/covers/<hash>" served by GET /covers/:filename.
 * Mirror the web client: pass absolute URLs through, prefix relative ones with
 * the server base URL.
 */
fun resolveCover(serverUrl: String, coverUrl: String?): String? = when {
    coverUrl.isNullOrBlank() -> null
    coverUrl.startsWith("http", ignoreCase = true) -> coverUrl
    else -> serverUrl.trimEnd('/') + "/" + coverUrl.trimStart('/')
}
