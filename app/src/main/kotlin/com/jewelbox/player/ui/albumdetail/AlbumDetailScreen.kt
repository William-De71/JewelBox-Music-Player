package com.jewelbox.player.ui.albumdetail

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jewelbox.player.data.net.AlbumDto
import com.jewelbox.player.data.net.TrackDto
import com.jewelbox.player.data.resolveCover

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: Int,
    onBack: () -> Unit,
    vm: AlbumDetailViewModel = viewModel(factory = AlbumDetailViewModel.Factory(albumId)),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = (state as? AlbumDetailUiState.Loaded)?.album?.title ?: "Album"
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is AlbumDetailUiState.Loading -> CircularProgressIndicator()

                is AlbumDetailUiState.Error -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text("Erreur : ${s.message}", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = vm::load) { Text("Réessayer") }
                }

                is AlbumDetailUiState.Loaded -> AlbumDetailContent(s.album, s.serverUrl)
            }
        }
    }
}

@Composable
private fun AlbumDetailContent(album: AlbumDto, serverUrl: String) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item { AlbumHeader(album, serverUrl) }

        if (album.tracks.isNotEmpty()) {
            item {
                Text(
                    "Pistes",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                )
            }
            items(items = album.tracks, key = { it.id }) { track ->
                TrackRow(track)
                HorizontalDivider()
            }
        } else {
            item {
                Text(
                    "Aucune piste pour cet album.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun AlbumHeader(album: AlbumDto, serverUrl: String) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val cover = resolveCover(serverUrl, album.coverUrl)
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = album.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Album,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier
                        .height(280.dp)
                        .size(120.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(album.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            album.artist.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        val meta = buildList {
            album.year?.let { add(it.toString()) }
            album.genre?.takeIf { it.isNotBlank() }?.let { add(it) }
            album.label?.name?.takeIf { it.isNotBlank() }?.let { add(it) }
            album.totalDuration?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        album.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            Spacer(Modifier.height(8.dp))
            Text(notes, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TrackRow(track: TrackDto) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = track.position.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (track.isFavorite) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Favori",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(18.dp),
            )
        }
        track.duration?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
