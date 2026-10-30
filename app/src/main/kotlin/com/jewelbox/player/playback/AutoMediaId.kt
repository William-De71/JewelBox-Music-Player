package com.jewelbox.player.playback

/**
 * Media ids of the Android Auto browse tree (AutoLibrary.kt). Auto only hands
 * back the id of what was tapped, so everything needed to load or play an item
 * is encoded in it. Pure — unit-tested.
 */
sealed interface AutoMediaId {
    /** Root and its tabs: browsable only. */
    data object Root : AutoMediaId
    data object Home : AutoMediaId
    data object Library : AutoMediaId
    data object Playlists : AutoMediaId
    data object Downloads : AutoMediaId

    /** An artist of the library: browsable, lists their albums. */
    data class Artist(val id: Int) : AutoMediaId

    /** Playable items: tapping one replaces the queue. */
    data class Album(val id: Int) : AutoMediaId
    data class Playlist(val id: Int) : AutoMediaId
    data class Smart(val key: String) : AutoMediaId

    /** Kept on the phone: played from the offline catalog, without the server. */
    data class DownloadedAlbum(val scope: String, val id: Int) : AutoMediaId
    data class DownloadedPlaylist(val scope: String, val id: Int) : AutoMediaId

    val value: String
        get() = when (this) {
            Root -> "root"
            Home -> "home"
            Library -> "library"
            Playlists -> "playlists"
            Downloads -> "downloads"
            is Artist -> "artist:$id"
            is Album -> "album:$id"
            is Playlist -> "playlist:$id"
            is Smart -> "smart:$key"
            // Id first: the scope ("<server id>/<collection>") may hold any character.
            is DownloadedAlbum -> "dl-album:$id:$scope"
            is DownloadedPlaylist -> "dl-playlist:$id:$scope"
        }

    companion object {
        fun parse(value: String): AutoMediaId? {
            when (value) {
                "root" -> return Root
                "home" -> return Home
                "library" -> return Library
                "playlists" -> return Playlists
                "downloads" -> return Downloads
            }
            val type = value.substringBefore(':', missingDelimiterValue = "")
            val rest = value.substringAfter(':')
            return when (type) {
                "artist" -> rest.toIntOrNull()?.let(::Artist)
                "album" -> rest.toIntOrNull()?.let(::Album)
                "playlist" -> rest.toIntOrNull()?.let(::Playlist)
                "smart" -> rest.takeIf { it.isNotEmpty() }?.let(::Smart)
                "dl-album" -> downloaded(rest, ::DownloadedAlbum)
                "dl-playlist" -> downloaded(rest, ::DownloadedPlaylist)
                else -> null
            }
        }

        private fun downloaded(rest: String, build: (String, Int) -> AutoMediaId): AutoMediaId? {
            val id = rest.substringBefore(':').toIntOrNull() ?: return null
            val scope = rest.substringAfter(':', missingDelimiterValue = "")
            return if (scope.isEmpty()) null else build(scope, id)
        }
    }
}

/** One page of [items] as Auto asks for it; an out-of-range page is empty. */
fun <T> pageOf(items: List<T>, page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return items
    val from = page.toLong() * pageSize
    if (from >= items.size) return emptyList()
    return items.subList(from.toInt(), minOf(items.size.toLong(), from + pageSize).toInt())
}
