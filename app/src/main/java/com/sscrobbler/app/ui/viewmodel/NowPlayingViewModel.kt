package com.sscrobbler.app.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sscrobbler.app.database.HistoryDao
import com.sscrobbler.app.database.HistoryItemEntity
import com.sscrobbler.app.media.PlaybackTracker
import com.sscrobbler.app.model.PlaybackSession
import com.sscrobbler.app.model.ScrobbleStatus
import com.sscrobbler.app.model.Track
import com.sscrobbler.app.scrobble.ScrobbleEngine
import com.sscrobbler.app.settings.AppSettings
import com.sscrobbler.app.settings.SettingsRepository
import com.sscrobbler.app.util.Clock
import com.sscrobbler.app.util.SystemClockImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class NowPlayingUiState(
    val track: Track? = null,
    val status: ScrobbleStatus = ScrobbleStatus.Listening,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val listenedMs: Long = 0L,
    val durationMs: Long? = null,
    val thresholdMs: Long = 120_000L,
    val minListenedPercent: Int = 50,
    val thresholdProgress: Float = 0f,
    val trackProgress: Float = 0f,
    val artwork: Bitmap? = null,
    val sourcePackage: String = "",
    val lastScrobbledTrack: HistoryItemEntity? = null
) {
    val isIdle: Boolean get() = track == null

    val listenedFormatted: String
        get() = formatTime(listenedMs)

    val durationFormatted: String
        get() = durationMs?.let { formatTime(it) } ?: "--:--"

    val thresholdFormatted: String
        get() = formatTime(thresholdMs)

    companion object {
        fun formatTime(ms: Long): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
    }
}

class NowPlayingViewModel(
    private val playbackTracker: PlaybackTracker,
    private val scrobbleEngine: ScrobbleEngine,
    private val settingsRepository: SettingsRepository,
    private val historyDao: HistoryDao,
    private val clock: Clock = SystemClockImpl
) : ViewModel() {

    private val lastScrobbledFlow = historyDao.getRecentFlow(1)

    val uiState: StateFlow<NowPlayingUiState> = combine(
        playbackTracker.activeTrackFlow,
        scrobbleEngine.statusFlow,
        playbackTracker.isPlayingFlow,
        playbackTracker.positionFlow,
        scrobbleEngine.currentSessionFlow,
        settingsRepository.settingsFlow,
        playbackTracker.artworkBitmapFlow,
        lastScrobbledFlow
    ) { args: Array<Any?> ->
        val track = args[0] as? Track
        val status = (args[1] as? ScrobbleStatus) ?: ScrobbleStatus.Listening
        val isPlaying = (args[2] as? Boolean) ?: false
        val positionMs = (args[3] as? Long) ?: 0L
        val session = args[4] as? PlaybackSession
        val settings = (args[5] as? AppSettings) ?: AppSettings()
        val artwork = args[6] as? Bitmap
        val recentList = args[7] as? List<*>
        val lastScrobbled = recentList?.firstOrNull() as? HistoryItemEntity

        val currentTrack = track ?: session?.track
        val durationMs = currentTrack?.durationMs
        val listenedMs = session?.totalListenedMs(clock) ?: 0L

        val thresholdMs = ScrobbleEngine.calculateThreshold(
            durationMs = durationMs,
            minListenedPercent = settings.minListenedPercent,
            maxRequiredTimeMs = settings.maxRequiredTimeMs
        )

        val thresholdProgress = if (thresholdMs > 0) {
            (listenedMs.toFloat() / thresholdMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

        val trackProgress = if (durationMs != null && durationMs > 0) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

        NowPlayingUiState(
            track = currentTrack,
            status = status,
            isPlaying = isPlaying,
            positionMs = positionMs,
            listenedMs = listenedMs,
            durationMs = durationMs,
            thresholdMs = thresholdMs,
            minListenedPercent = settings.minListenedPercent,
            thresholdProgress = thresholdProgress,
            trackProgress = trackProgress,
            artwork = artwork,
            sourcePackage = currentTrack?.sourcePackage ?: "",
            lastScrobbledTrack = lastScrobbled
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NowPlayingUiState()
    )

    class Factory(
        private val playbackTracker: PlaybackTracker,
        private val scrobbleEngine: ScrobbleEngine,
        private val settingsRepository: SettingsRepository,
        private val historyDao: HistoryDao,
        private val clock: Clock = SystemClockImpl
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NowPlayingViewModel(
                playbackTracker = playbackTracker,
                scrobbleEngine = scrobbleEngine,
                settingsRepository = settingsRepository,
                historyDao = historyDao,
                clock = clock
            ) as T
        }
    }
}
