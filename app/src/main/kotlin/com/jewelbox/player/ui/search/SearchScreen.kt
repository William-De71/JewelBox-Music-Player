package com.jewelbox.player.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jewelbox.player.R
import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.QueueTrackDto
import com.jewelbox.player.data.resolveCover
import com.jewelbox.player.playback.PlayerConnection
import com.jewelbox.player.ui.playlists.QueueTrackRow
import com.jewelbox.player.ui.playlists.noticeText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenAlbum: (Int) -> Unit,
    bottomBar: @Composable () -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    notice?.let { n ->
        val text = noticeText(n)
        LaunchedEffect(n) {
            snackbarHostState.showSnackbar(text)
            vm.consumeNotice()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.search_title)) }) },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::onQueryChange,
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.search_clear),
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.searchNow() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                when (val s = state) {
                    is SearchUiState.Idle -> CenteredText(stringResource(R.string.search_prompt))

                    is SearchUiState.NoServer -> CenteredText(stringResource(R.string.no_server_configured))

                    is SearchUiState.Loading -> CircularProgressIndicator()

                    is SearchUiState.Error -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = if (s.serverTooOld) {
                                stringResource(R.string.search_server_too_old)
                            } else {
                                stringResource(
                                    R.string.error_with_detail,
                                    s.message ?: stringResource(R.string.load_failed),
                                )
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = vm::searchNow) { Text(stringResource(R.string.retry)) }
                    }

                    is SearchUiState.Loaded -> {
                        if (s.albums.isEmpty() && s.tracks.isEmpty()) {
                            CenteredText(stringResource(R.string.search_no_results))
                        } else {
                            SearchResults(
                                state = s,
                                onOpenAlbum = onOpenAlbum,
                                onToggleFavorite = vm::toggleFavorite,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchUiState.Loaded,
    onOpenAlbum: (Int) -> Unit,
    onToggleFavorite: (Int) -> Unit,
) {
    val playback by PlayerConnection.state.collectAsStateWithLifecycle()
    val playable = state.tracks.filter { it.hasFile }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        if (state.albums.isNotEmpty()) {
            item(key = "albums-header") { SectionHeader(stringResource(R.string.search_albums_header)) }
            itemsIndexed(items = state.albums, key = { _, album -> "album-${album.id}" }) { _, album ->
                SearchAlbumRow(
                    album = album,
                    serverUrl = state.serverUrl,
                    onClick = { onOpenAlbum(album.id) },
                )
            }
        }

        if (state.tracks.isNotEmpty()) {
            item(key = "tracks-header") { SectionHeader(stringResource(R.string.search_tracks_header)) }
            itemsIndexed(items = state.tracks, key = { _, track -> "track-${track.id}" }) { index, track ->
                val isCurrent = playback.currentTrackId == track.id
                QueueTrackRow(
                    track = track,
                    position = index + 1,
                    isCurrent = isCurrent,
                    onPlay = {
                        if (isCurrent) {
                            PlayerConnection.togglePlayPause()
                        } else {
                            val start = playable.indexOfFirst { it.id == track.id }
                            PlayerConnection.playQueue(state.serverUrl, state.tracks, start)
                        }
                    },
                    onToggleFavorite = { onToggleFavorite(track.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** Compact result row: thumbnail cover, title over "artist · year". */
@Composable
private fun SearchAlbumRow(
    album: AlbumDto,
    serverUrl: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val cover = resolveCover(serverUrl, album.coverUrl)
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = album.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Album,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.fillMaxSize(0.6f),
                )
            }
        }
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 12.dp)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(album.artist.name, album.year?.toString())
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}
