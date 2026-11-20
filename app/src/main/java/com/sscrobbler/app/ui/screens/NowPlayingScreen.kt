package com.sscrobbler.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.sscrobbler.app.database.HistoryItemEntity
import com.sscrobbler.app.model.Track
import com.sscrobbler.app.ui.components.StatusBadge
import com.sscrobbler.app.ui.theme.PillShape
import com.sscrobbler.app.ui.viewmodel.NowPlayingUiState
import com.sscrobbler.app.ui.viewmodel.NowPlayingViewModel

@Composable
fun NowPlayingScreen(viewModel: NowPlayingViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    NowPlayingContent(state, modifier)
}

@Composable
internal fun NowPlayingContent(state: NowPlayingUiState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= 600.dp
        val coverSize = if (wide) 208.dp else (maxHeight * 0.41f).coerceIn(148.dp, 320.dp)
        Column(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Now playing", style = MaterialTheme.typography.headlineLarge)
            state.connectionError?.let { error ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.CloudOff, null)
                        Text(error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            val track = state.track
            if (track == null) {
                IdleStateCard(state.lastScrobbledTrack)
            } else {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    if (wide) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            CoverArt(track, state.artwork, state.artworkUrl, state.isPlaying, Modifier.size(coverSize))
                            TrackDetails(state, track, Modifier.weight(1f))
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CoverArt(track, state.artwork, state.artworkUrl, state.isPlaying,
                                Modifier.align(Alignment.CenterHorizontally).size(coverSize))
                            TrackDetails(state, track, Modifier.fillMaxWidth())
                        }
                    }
                }
                key(track.artist, track.title, track.sourcePackage) { ListeningProgress(state) }
            }
        }
    }
}

private data class Cover(val bitmap: Bitmap?, val url: String?, val artist: String, val title: String)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CoverArt(track: Track, bitmap: Bitmap?, url: String?, playing: Boolean, modifier: Modifier) {
    val corner by animateDpAsState(if (playing) 44.dp else 16.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(), label = "cover corners")
    val shape = RoundedCornerShape(topStart = corner, topEnd = 16.dp, bottomEnd = corner, bottomStart = 16.dp)
    Crossfade(
        targetState = Cover(bitmap, url, track.artist, track.title),
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
        label = "album cover"
    ) { cover ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Record(Modifier.fillMaxSize(0.78f))
            if (cover.bitmap != null) {
                Image(remember(cover.bitmap) { cover.bitmap.asImageBitmap() }, null,
                    Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else if (!cover.url.isNullOrBlank()) {
                AsyncImage(model = cover.url, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop)
            }
        }
    }
}

@Composable
private fun Record(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(colors.onPrimaryContainer, r)
        for (groove in 0..5) {
            drawCircle(colors.primaryContainer.copy(alpha = 0.23f), r * (0.48f + groove * 0.086f),
                style = Stroke(width = 1.dp.toPx()))
        }
        drawCircle(colors.tertiaryContainer, r * 0.37f)
        drawCircle(colors.tertiary, r * 0.075f)
        drawCircle(colors.onTertiaryContainer, r * 0.028f, Offset(center.x, center.y - r * 0.21f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackDetails(state: NowPlayingUiState, track: Track, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusBadge(state.status, showSubtext = false)
            if (track.sourcePackage.isNotBlank()) SourceAppChip(track.sourcePackage)
        }
        AnimatedContent(targetState = track, label = "track details") { item ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(item.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                state.durationMs?.let {
                    Text("Track length · ${state.durationFormatted}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item.album?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SourceAppChip(packageName: String) {
    val prettyName = when (packageName) {
        "com.spotify.music" -> "Spotify"
        "com.google.android.apps.youtube.music" -> "YouTube Music"
        "com.apple.android.music" -> "Apple Music"
        "deezer.android.app" -> "Deezer"
        "com.aspiro.tidal" -> "TIDAL"
        "com.qobuz.music" -> "Qobuz"
        "com.soundcloud.android" -> "SoundCloud"
        "com.maxmpz.audioplayer" -> "Poweramp"
        "app.symfonium.music.player" -> "Symfonium"
        "in.krosbits.musicolet" -> "Musicolet"
        "org.videolan.vlc" -> "VLC"
        else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    Surface(
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Headphones,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = prettyName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ListeningProgress(state: NowPlayingUiState) {
    val progress by animateFloatAsState(state.thresholdProgress,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "listened time")
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Listening time", style = MaterialTheme.typography.labelLarge)
            Text(state.listenedFormatted, style = MaterialTheme.typography.displaySmall)
            if (state.tooShort) {
                Text("Below your minimum track length", style = MaterialTheme.typography.bodyMedium)
            } else {
                LinearWavyProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Scrobble progress" },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.18f),
                    amplitude = { if (state.isPlaying && it < 1f) 1f else 0f },
                    waveSpeed = if (state.isPlaying && state.thresholdProgress < 1f) 16.dp else 0.dp
                )
                val detail = if (state.thresholdProgress >= 1f) "Submits when playback ends"
                    else "Scrobble threshold · ${state.thresholdFormatted}"
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun IdleStateCard(recent: HistoryItemEntity?) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Record(Modifier.size(100.dp))
            Spacer(Modifier.height(4.dp))
            Text("Waiting for music", style = MaterialTheme.typography.headlineMedium)
            Text("Start a track in your music player to see it here.", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (recent != null) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Last played", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(recent.title, style = MaterialTheme.typography.titleLarge)
                Text(recent.artist, style = MaterialTheme.typography.bodyMedium)
                StatusBadge(recent.status, showSubtext = false)
            }
        }
    }
}
