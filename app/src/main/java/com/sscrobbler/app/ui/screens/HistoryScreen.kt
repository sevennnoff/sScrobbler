package com.sscrobbler.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.sscrobbler.app.lastfm.LastFmHistoryTrack
import com.sscrobbler.app.ui.components.StatusBadge
import com.sscrobbler.app.ui.viewmodel.HistoryViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceItems by viewModel.deviceHistory.collectAsStateWithLifecycle(initialValue = null)
    var device by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val local = device || state.isLocal
    val items = if (local) deviceItems ?: if (state.isLocal) state.items else emptyList() else state.items
    val zone = ZoneId.systemDefault()
    val days = remember(items, zone) {
        items.groupBy { if (it.isNowPlaying) null else Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
    }
    val loading = if (device) deviceItems == null else state.isLoading && items.isEmpty()

    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("History", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                if (!device) {
                    FilledTonalIconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing && !state.isLoading) {
                        if (state.isRefreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "Refresh history")
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                listOf("Last.fm", "On device").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = device == (index == 1),
                        onClick = { device = index == 1 },
                        shape = SegmentedButtonDefaults.itemShape(index, 2)
                    ) { Text(label) }
                }
            }
            if (!device) state.errorMessage?.let { message ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(message, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(if (local) "No plays saved on this device yet" else "No scrobbles yet",
                        style = MaterialTheme.typography.titleMedium)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    days.forEach { (date, tracks) ->
                        item(key = "section:$date") {
                            val today = LocalDate.now(zone)
                            val heading = when (date) {
                                null -> "Now playing"
                                today -> "Today"
                                today.minusDays(1) -> "Yesterday"
                                else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                            }
                            Text(heading, Modifier.padding(top = 14.dp, bottom = 5.dp),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        itemsIndexed(tracks, key = { _, item -> item.id }) { index, item ->
                            val top = if (index == 0) 22.dp else 6.dp
                            val bottom = if (index == tracks.lastIndex) 22.dp else 6.dp
                            val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
                            Surface(modifier = Modifier.animateItem().fillMaxWidth(), shape = shape,
                                color = if (item.isNowPlaying) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer) {
                                HistoryRow(item, zone)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: LastFmHistoryTrack, zone: ZoneId) {
    Row(Modifier.fillMaxWidth().animateContentSize().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(54.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                if (!item.artworkUrl.isNullOrBlank()) AsyncImage(item.artworkUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(item.artist, item.album?.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!item.isNowPlaying) {
                val time = remember(item.timestamp, zone) {
                    Instant.ofEpochSecond(item.timestamp).atZone(zone).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
                }
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.localStatus?.let { StatusBadge(it, Modifier.padding(top = 4.dp), showSubtext = false) }
        }
    }
}
