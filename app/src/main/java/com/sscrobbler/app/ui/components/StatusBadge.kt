package com.sscrobbler.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sscrobbler.app.model.ScrobbleStatus
import com.sscrobbler.app.ui.theme.PillShape
import com.sscrobbler.app.ui.theme.StatusEligibleColor
import com.sscrobbler.app.ui.theme.StatusEligibleContainer
import com.sscrobbler.app.ui.theme.StatusListeningColor
import com.sscrobbler.app.ui.theme.StatusListeningContainer
import com.sscrobbler.app.ui.theme.StatusOfflineColor
import com.sscrobbler.app.ui.theme.StatusOfflineContainer
import com.sscrobbler.app.ui.theme.StatusPausedColor
import com.sscrobbler.app.ui.theme.StatusPausedContainer
import com.sscrobbler.app.ui.theme.StatusScrobbledColor
import com.sscrobbler.app.ui.theme.StatusScrobbledContainer
import com.sscrobbler.app.ui.theme.StatusSkippedColor
import com.sscrobbler.app.ui.theme.StatusSkippedContainer

@Composable
fun StatusBadge(
    status: ScrobbleStatus,
    modifier: Modifier = Modifier,
    isOnline: Boolean = true,
    showSubtext: Boolean = true
) {
    val isOfflinePending = (!isOnline && status == ScrobbleStatus.Pending)

    val (containerColor, contentColor, icon, label, subtext) = when {
        isOfflinePending -> Quintuple(
            StatusOfflineContainer,
            StatusOfflineColor,
            Icons.Default.CloudOff,
            "Offline",
            "Waiting for network"
        )
        status == ScrobbleStatus.Listening -> Quintuple(
            StatusListeningContainer,
            StatusListeningColor,
            Icons.Default.PlayArrow,
            "Listening",
            "In progress"
        )
        status == ScrobbleStatus.Paused -> Quintuple(
            StatusPausedContainer,
            StatusPausedColor,
            Icons.Default.Pause,
            "Paused",
            "On pause"
        )
        status == ScrobbleStatus.Eligible || status == ScrobbleStatus.WaitingForEnd -> Quintuple(
            StatusEligibleContainer,
            StatusEligibleColor,
            Icons.Default.HourglassTop,
            "Ready to Scrobble",
            "Will scrobble on track end"
        )
        status == ScrobbleStatus.Scrobbled -> Quintuple(
            StatusScrobbledContainer,
            StatusScrobbledColor,
            Icons.Default.CheckCircle,
            "Scrobbled",
            "Sent to Last.fm"
        )
        status == ScrobbleStatus.Skipped -> Quintuple(
            StatusSkippedContainer,
            StatusSkippedColor,
            Icons.Default.SkipNext,
            "Skipped",
            "Below threshold"
        )
        status == ScrobbleStatus.Pending -> Quintuple(
            StatusListeningContainer,
            StatusListeningColor,
            Icons.Default.HourglassTop,
            "Pending",
            "Queued for upload"
        )
        else -> Quintuple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            Icons.Default.CloudOff,
            "Failed",
            "Upload failed"
        )
    }

    Surface(
        modifier = modifier,
        shape = PillShape,
        color = containerColor,
        shadowElevation = if (status == ScrobbleStatus.Eligible || status == ScrobbleStatus.WaitingForEnd) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(contentColor.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = label,
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                if (showSubtext && subtext.isNotBlank()) {
                    Text(
                        text = "•  $subtext",
                        color = contentColor.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
