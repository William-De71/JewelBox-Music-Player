package com.jewelbox.player.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.jewelbox.player.R
import com.jewelbox.player.playback.PlayerConnection

/**
 * Compact playback bar shown at the bottom of screens whenever a track is loaded.
 * Reads its state straight from [PlayerConnection] so every screen shares it.
 * Tapping it opens the full now-playing screen (the transport buttons consume
 * their own clicks, so they don't trigger [onOpen]).
 */
@Composable
fun MiniPlayer(
    onOpen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    // False when a NavigationBar sits below: that bar already absorbs the
    // system navigation-bar inset, so the mini-player must not add it twice.
    padBottomInset: Boolean = true,
) {
    val state by PlayerConnection.state.collectAsStateWithLifecycle()
    val position by PlayerConnection.position.collectAsStateWithLifecycle()
    if (!state.hasItem) return

    Surface(
        tonalElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            // Horizontal swipe changes track: left → next, right → previous.
            .pointerInput(Unit) {
                val threshold = 64.dp.toPx()
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { _, delta -> total += delta },
                    onDragEnd = {
                        when {
                            total < -threshold -> PlayerConnection.next()
                            total > threshold -> PlayerConnection.previous()
                        }
                    },
                )
            },
    ) {
        Column {
            // Thin read-only progress line (no timestamps) across the full width.
            val fraction = if (position.durationMs > 0) {
                (position.positionMs.toFloat() / position.durationMs).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(
                progress = { fraction },
                drawStopIndicator = {},
                gapSize = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    // Edge-to-edge: lift the content above the system navigation bar
                    // while the surface background still fills behind it.
                    .then(
                        if (padBottomInset) Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                        else Modifier,
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                if (state.artworkUrl != null) {
                    AsyncImage(
                        model = state.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Album,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(44.dp),
                    )
                }

                Column(
                    modifier = Modifier
                        .padding(horizontal = 10.dp)
                        .weight(1f),
                ) {
                    Text(
                        text = state.title.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.artist.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // No previous/next buttons: horizontal swipes on the bar do that.
                IconButton(onClick = PlayerConnection::toggleFavorite) {
                    Icon(
                        imageVector = if (state.isFavorite) Icons.Filled.Favorite
                                      else Icons.Filled.FavoriteBorder,
                        contentDescription = if (state.isFavorite) stringResource(R.string.unfavorite)
                                             else stringResource(R.string.favorite),
                        tint = if (state.isFavorite) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = PlayerConnection::togglePlayPause) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                    )
                }
            }
        }
    }
}
