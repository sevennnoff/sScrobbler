package com.sscrobbler.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sscrobbler.app.BuildConfig
import com.sscrobbler.app.ui.theme.CardShape
import com.sscrobbler.app.ui.theme.ChipShape
import com.sscrobbler.app.ui.viewmodel.SettingsUiState
import com.sscrobbler.app.util.DebugLog
import com.sscrobbler.app.util.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val logTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun LogEntry.timeText(): String = logTimeFormat.format(Date(timestampMs))

private fun LogEntry.isError(): Boolean {
    val lower = message.lowercase(Locale.US)
    return tag == "ERROR" || "error" in lower || "fail" in lower || "skip" in lower
}

private fun tagColor(tag: String): Color = when (tag) {
    "API" -> Color(0xFF7DD3FC)
    "ENGINE" -> Color(0xFF86EFAC)
    "TRACK" -> Color(0xFFFDE68A)
    "NP" -> Color(0xFFC4B5FD)
    "ERROR" -> Color(0xFFFCA5A5)
    else -> Color(0xFFE5E7EB)
}

@Composable
fun DebugScreen(
    settingsState: SettingsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val entries by DebugLog.entries.collectAsState()
    var filter by remember { mutableStateOf("All") }
    val listState = rememberLazyListState()

    val filtered = remember(entries, filter) {
        when (filter) {
            "Track" -> entries.filter { it.tag == "TRACK" }
            "Engine" -> entries.filter { it.tag == "ENGINE" }
            "API" -> entries.filter { it.tag == "API" }
            "NP" -> entries.filter { it.tag == "NP" }
            "Errors" -> entries.filter { it.isError() }
            else -> entries
        }
    }

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Debug logs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${filtered.size}/${entries.size} entries",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { copyLogs(context) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs")
            }
            IconButton(onClick = { shareLogs(context) }) {
                Icon(Icons.Default.Share, contentDescription = "Share logs")
            }
            IconButton(onClick = { DebugLog.clear() }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Session summary
        Card(
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DebugInfoLine("Account", settingsState.username ?: "not connected")
                DebugInfoLine("Now Playing", if (settingsState.sendNowPlaying) "enabled" else "disabled")
                DebugInfoLine("Threshold", "${settingsState.minListenedPercent}% / max ${settingsState.maxRequiredTimeMs / 1000}s, min track ${settingsState.minTrackDurationMs / 1000}s")
                DebugInfoLine("Pause timeout", "${settingsState.pauseTimeoutMs / 1000 / 60} min")
                DebugInfoLine("Allowed apps", "${settingsState.appSources.count { it.isEnabled }} of ${settingsState.appSources.size}")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Filters
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            listOf("All", "Track", "Engine", "API", "NP", "Errors").forEach { label ->
                FilterChip(
                    selected = filter == label,
                    onClick = { filter = label },
                    label = { Text(label, fontSize = 11.sp) },
                    shape = ChipShape,
                    colors = FilterChipDefaults.filterChipColors()
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No log entries yet.\nPlay something and come back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), CardShape)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filtered) { entry ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = entry.timeText(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(76.dp)
                        )
                        Surface(
                            shape = CircleShape,
                            color = tagColor(entry.tag).copy(alpha = 0.16f),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                text = entry.tag,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = tagColor(entry.tag),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                        Text(
                            text = entry.message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = if (entry.isError()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugInfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun copyLogs(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("sScrobbler debug log", DebugLog.export()))
    android.widget.Toast.makeText(context, "Logs copied", android.widget.Toast.LENGTH_SHORT).show()
}

private fun shareLogs(context: Context) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "sScrobbler debug log v${BuildConfig.VERSION_NAME}")
        putExtra(Intent.EXTRA_TEXT, DebugLog.export())
    }
    context.startActivity(Intent.createChooser(intent, "Share debug log"))
}
