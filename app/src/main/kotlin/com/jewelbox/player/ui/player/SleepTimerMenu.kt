package com.jewelbox.player.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jewelbox.player.R
import com.jewelbox.player.playback.PlayerConnection
import com.jewelbox.player.playback.SLEEP_TIMER_MINUTES
import com.jewelbox.player.playback.SleepTimer
import com.jewelbox.player.playback.sleepTimerFrom
import kotlinx.coroutines.delay

/** Moon button of the now-playing top bar: picks a sleep timer, shows what is left. */
@Composable
fun SleepTimerAction() {
    val timer by PlayerConnection.sleepTimer.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Countdown refresh, only while a timed timer runs.
    LaunchedEffect(timer) {
        while (timer is SleepTimer.Timed) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val label = when (val t = timer) {
        null -> null
        SleepTimer.EndOfTrack -> stringResource(R.string.sleep_timer_end_of_track_short)
        is SleepTimer.Timed -> formatRemaining(((t.endsAtMs - now).coerceAtLeast(0) + 999) / 1000)
    }
    val pick = { minutes: Int?, endOfTrack: Boolean ->
        PlayerConnection.setSleepTimer(sleepTimerFrom(minutes, endOfTrack, System.currentTimeMillis()))
        open = false
    }

    Box {
        TextButton(onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (timer != null) Icons.Filled.Bedtime else Icons.Outlined.Bedtime,
                    contentDescription = stringResource(R.string.sleep_timer_title),
                    tint = if (timer != null) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (label != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.sleep_timer_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            SLEEP_TIMER_MINUTES.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sleep_timer_minutes, minutes)) },
                    onClick = { pick(minutes, false) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sleep_timer_end_of_track)) },
                onClick = { pick(null, true) },
            )
            if (timer != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sleep_timer_cancel), color = MaterialTheme.colorScheme.error) },
                    onClick = { pick(0, false) },
                )
            }
        }
    }
}

private fun formatRemaining(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
