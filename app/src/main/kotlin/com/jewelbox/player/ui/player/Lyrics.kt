package com.jewelbox.player.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jewelbox.player.R
import com.jewelbox.player.ServiceLocator
import com.jewelbox.player.data.net.LyricLineDto
import com.jewelbox.player.data.net.LyricsDto

/** A small lead so the highlight does not lag behind the voice (same as the PWA). */
private const val LEAD_SEC = 0.3

/** The line being sung: the last one started at [positionSec], -1 before the first. */
fun currentLineIndex(lines: List<LyricLineDto>, positionSec: Double): Int {
    var lo = 0
    var hi = lines.lastIndex
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].time <= positionSec + LEAD_SEC) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

/** What the server answered per track this session: asked once each. */
private val lyricsCache = mutableMapOf<Int, LyricsDto>()

private sealed interface LyricsState {
    data object Loading : LyricsState
    data object Unavailable : LyricsState
    data class Loaded(val lyrics: LyricsDto) : LyricsState
}

/**
 * The lyrics of the playing track, in place of the artwork: synced lines
 * follow the music (a tap seeks there), plain lyrics scroll as text.
 */
@Composable
fun LyricsView(trackId: Int, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var state by remember(trackId) { mutableStateOf<LyricsState>(LyricsState.Loading) }
    LaunchedEffect(trackId) {
        val lyrics = lyricsCache[trackId]
            ?: runCatching { ServiceLocator.albumRepository.lyrics(trackId) }.getOrNull()
                ?.also { lyricsCache[trackId] = it }
        state = if (lyrics != null) LyricsState.Loaded(lyrics) else LyricsState.Unavailable
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val s = state) {
            LyricsState.Loading -> Message(stringResource(R.string.lyrics_loading))
            LyricsState.Unavailable -> Message(stringResource(R.string.lyrics_unavailable))
            is LyricsState.Loaded -> {
                val lyrics = s.lyrics
                val lines = lyrics.lines.orEmpty()
                when {
                    lyrics.instrumental -> Message(stringResource(R.string.lyrics_instrumental))
                    lyrics.synced && lines.isNotEmpty() -> SyncedLyrics(lines, positionMs, onSeek)
                    !lyrics.plain.isNullOrBlank() -> Text(
                        lyrics.plain,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    )
                    else -> Message(stringResource(R.string.lyrics_none))
                }
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLineDto>, positionMs: Long, onSeek: (Long) -> Unit) {
    val current = currentLineIndex(lines, positionMs / 1000.0)
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 24.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lines) { index, line ->
            val active = index == current
            Text(
                text = line.text.ifBlank { "♪" },
                style = if (active) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    active -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (index < current) 0.5f else 0.8f)
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek((line.time * 1000).toLong()) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(24.dp),
    )
}
