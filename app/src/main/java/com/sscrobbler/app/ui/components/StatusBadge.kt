package com.sscrobbler.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sscrobbler.app.model.ScrobbleStatus
import com.sscrobbler.app.ui.theme.PillShape

private data class Badge(val label: String, val detail: String, val icon: ImageVector, val fill: Color, val ink: Color)

@Composable
fun StatusBadge(status: ScrobbleStatus, modifier: Modifier = Modifier, isOnline: Boolean = true, showSubtext: Boolean = true) {
    val c = MaterialTheme.colorScheme
    val badge = when (status) {
        ScrobbleStatus.Listening -> Badge("Listening", "Playback in progress", Icons.Default.PlayArrow, c.primary, c.onPrimary)
        ScrobbleStatus.Paused -> Badge("Paused", "Listening time is paused", Icons.Default.Pause, c.surfaceContainerHighest, c.onSurface)
        ScrobbleStatus.Eligible, ScrobbleStatus.WaitingForEnd ->
            Badge("Ready to scrobble", "Submits when playback ends", Icons.Default.HourglassTop, c.tertiaryContainer, c.onTertiaryContainer)
        ScrobbleStatus.Scrobbled -> Badge("Scrobbled", "Confirmed by Last.fm", Icons.Default.CheckCircle, c.primaryContainer, c.onPrimaryContainer)
        ScrobbleStatus.Skipped -> Badge("Skipped", "Below threshold", Icons.Default.SkipNext, c.surfaceContainerHighest, c.onSurfaceVariant)
        ScrobbleStatus.Pending -> Badge(if (isOnline) "Queued" else "Offline", "Waiting to upload", Icons.Default.HourglassTop, c.secondaryContainer, c.onSecondaryContainer)
        ScrobbleStatus.Failed -> Badge("Failed", "Rejected by Last.fm", Icons.Default.CloudOff, c.errorContainer, c.onErrorContainer)
    }
    val fill by animateColorAsState(badge.fill, label = "status background")
    Surface(modifier.semantics(mergeDescendants = true) {}, shape = PillShape, color = fill, contentColor = badge.ink) {
        Row(Modifier.animateContentSize().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(badge.icon, null, Modifier.size(16.dp))
            Column {
                Text(badge.label, style = MaterialTheme.typography.labelLarge)
                if (showSubtext) Text(badge.detail, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
