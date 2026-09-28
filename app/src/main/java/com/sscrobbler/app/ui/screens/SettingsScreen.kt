package com.sscrobbler.app.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sscrobbler.app.ui.theme.CardShape
import com.sscrobbler.app.ui.theme.ChipShape
import com.sscrobbler.app.ui.theme.PillShape
import com.sscrobbler.app.ui.viewmodel.SettingsUiState
import com.sscrobbler.app.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToApps: () -> Unit,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkPermissions(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // 1. Last.fm Account Card
        LastFmAccountCard(
            state = state,
            onReconnect = onNavigateToLogin,
            onDisconnect = { viewModel.disconnectLastFm() }
        )

        // 2. Scrobbling Preferences Card
        ScrobblingPreferencesCard(
            state = state,
            viewModel = viewModel
        )

        // 3. Apps / Media Sources Navigation Card
        AppsNavigationCard(
            state = state,
            onClick = onNavigateToApps
        )

        // 4. Battery & Permissions Section
        BatteryAndPermissionsSection(
            state = state,
            context = context,
            viewModel = viewModel
        )
    }
}

@Composable
fun LastFmAccountCard(
    state: SettingsUiState,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (state.isLoggedIn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(52.dp)
                ) {
                    if (!state.avatarUrl.isNullOrBlank()) {
                        coil.compose.AsyncImage(
                            model = state.avatarUrl,
                            contentDescription = "User Avatar",
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = if (state.isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Last.fm Account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (state.isLoggedIn && !state.username.isNullOrBlank()) {
                            "@${state.username}"
                        } else {
                            "Not connected"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }

                Surface(
                    shape = PillShape,
                    color = if (state.isLoggedIn) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = if (state.isLoggedIn) "Connected" else "Disconnected",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.isLoggedIn) {
                    OutlinedButton(
                        onClick = onReconnect,
                        modifier = Modifier.weight(1f),
                        shape = PillShape
                    ) {
                        Text("Reconnect")
                    }
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f),
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Disconnect", color = MaterialTheme.colorScheme.onError)
                    }
                } else {
                    Button(
                        onClick = onReconnect,
                        modifier = Modifier.fillMaxWidth(),
                        shape = PillShape
                    ) {
                        Text("Connect Last.fm")
                    }
                }
            }
        }
    }
}

@Composable
fun ScrobblingPreferencesCard(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Scrobbling Preferences",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // 1. Min Listened Percent Slider
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Minimum listened percentage",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${state.minListenedPercent}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = state.minListenedPercent.toFloat(),
                    onValueChange = { viewModel.updateMinListenedPercent(it.toInt()) },
                    valueRange = 10f..100f,
                    steps = 17
                )
            }

            // 2. Maximum required listening time selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Maximum required listening time",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        120_000L to "2 min",
                        180_000L to "3 min",
                        240_000L to "4 min",
                        300_000L to "5 min"
                    ).forEach { (ms, label) ->
                        FilterChip(
                            selected = state.maxRequiredTimeMs == ms,
                            onClick = { viewModel.updateMaxRequiredTimeMs(ms) },
                            label = { Text(label) },
                            shape = PillShape
                        )
                    }
                }
            }

            // 3. Minimum track duration selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Minimum track duration",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        15_000L to "15s",
                        30_000L to "30s",
                        60_000L to "60s"
                    ).forEach { (ms, label) ->
                        FilterChip(
                            selected = state.minTrackDurationMs == ms,
                            onClick = { viewModel.updateMinTrackDurationMs(ms) },
                            label = { Text(label) },
                            shape = PillShape
                        )
                    }
                }
            }

            // 4. Paused track timeout selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Paused track timeout",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        300_000L to "5 min",
                        900_000L to "15 min",
                        1_800_000L to "30 min",
                        3_600_000L to "1 hour"
                    ).forEach { (ms, label) ->
                        FilterChip(
                            selected = state.pauseTimeoutMs == ms,
                            onClick = { viewModel.updatePauseTimeoutMs(ms) },
                            label = { Text(label) },
                            shape = PillShape
                        )
                    }
                }
            }

            // 5. Send Now Playing toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Send Now Playing",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Display currently playing track on Last.fm profile",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.sendNowPlaying,
                    onCheckedChange = { viewModel.updateSendNowPlaying(it) }
                )
            }
        }
    }
}

@Composable
fun AppsNavigationCard(
    state: SettingsUiState,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = ChipShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Apps & Media Sources",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${state.appSources.count { it.isEnabled }} active players configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun BatteryAndPermissionsSection(
    state: SettingsUiState,
    context: Context,
    viewModel: SettingsViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Battery & Permissions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Notification Access Card
        PermissionStatusCard(
            title = "Notification access",
            subtitle = "Required to detect playback from Spotify, Qobuz, etc.",
            isGranted = state.isNotificationAccessGranted,
            grantedText = "Granted",
            missingText = "Missing",
            grantedIcon = Icons.Default.Notifications,
            missingIcon = Icons.Default.NotificationsOff,
            onClick = { viewModel.openNotificationSettings(context) }
        )

        // Battery Optimization Card
        PermissionStatusCard(
            title = "Battery optimization",
            subtitle = "Ensure background service is not killed by OS",
            isGranted = state.isBatteryOptimizationIgnored,
            grantedText = "Disabled (Unlimited)",
            missingText = "Enabled (May sleep)",
            grantedIcon = Icons.Default.BatteryChargingFull,
            missingIcon = Icons.Default.BatteryAlert,
            onClick = { viewModel.openBatteryOptimizationSettings(context) }
        )
    }
}

@Composable
fun PermissionStatusCard(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    grantedText: String,
    missingText: String,
    grantedIcon: ImageVector,
    missingIcon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = ChipShape,
                color = if (isGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isGranted) grantedIcon else missingIcon,
                        contentDescription = null,
                        tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                shape = PillShape,
                color = if (isGranted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            ) {
                Text(
                    text = if (isGranted) grantedText else missingText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}
