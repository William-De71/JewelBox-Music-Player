package com.jewelbox.player.ui.playlists

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.graphics.vector.ImageVector
import com.jewelbox.player.R

/**
 * Display order, icon and label of the smart playlists — fixed client-side like
 * in the PWA (client/src/pages/Playlists.jsx#SMART_KEYS/#SMART_ICONS); the
 * server only supplies the per-key track counts.
 */
data class SmartSpec(
    val key: String,
    val icon: ImageVector,
    @StringRes val label: Int,
)

val SMART_SPECS = listOf(
    SmartSpec("newest", Icons.Filled.AutoAwesome, R.string.smart_newest),
    SmartSpec("ever_played", Icons.Filled.History, R.string.smart_ever_played),
    SmartSpec("never_played", Icons.Filled.MusicNote, R.string.smart_never_played),
    SmartSpec("last_played", Icons.Filled.Schedule, R.string.smart_last_played),
    SmartSpec("most_played", Icons.Filled.BarChart, R.string.smart_most_played),
    SmartSpec("favourites", Icons.Filled.Favorite, R.string.smart_favourites),
    SmartSpec("all_tracks", Icons.Filled.LibraryMusic, R.string.smart_all_tracks),
    SmartSpec("dynamic_mix", Icons.Filled.AllInclusive, R.string.smart_dynamic_mix),
)

const val DYNAMIC_MIX_KEY = "dynamic_mix"

fun smartSpec(key: String): SmartSpec? = SMART_SPECS.firstOrNull { it.key == key }
