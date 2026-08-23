package com.forge.audiobookforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forge.audiobookforge.di.LocalAppContainer
import com.forge.audiobookforge.playback.PlayerController

@Composable
fun MiniPlayer(ui: PlayerController.PlayerUi) {
    val container = LocalAppContainer.current
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Slider(
                value = if (ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs else 0f,
                onValueChange = { frac -> container.player.seekTo((frac * ui.durationMs).toLong()) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, bottom = 6.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(ui.bookTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        "${ui.chapterTitle} · ${fmtMs(ui.positionMs)} / ${fmtMs(ui.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { container.player.previous() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous chapter")
                }
                IconButton(onClick = { container.player.togglePlayPause() }) {
                    Icon(
                        if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/pause",
                    )
                }
                IconButton(onClick = { container.player.next() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next chapter")
                }
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close player",
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .clickable { container.player.dismiss() },
                )
            }
        }
    }
}

private fun fmtMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
