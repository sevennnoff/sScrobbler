package com.sscrobbler.app.scrobble

import com.sscrobbler.app.database.HistoryDao
import com.sscrobbler.app.database.HistoryItemEntity
import com.sscrobbler.app.database.PendingScrobbleDao
import com.sscrobbler.app.database.PendingScrobbleEntity
import com.sscrobbler.app.model.PlaybackSession
import com.sscrobbler.app.model.ScrobbleStatus
import com.sscrobbler.app.model.Track
import com.sscrobbler.app.settings.AppSettings
import com.sscrobbler.app.settings.SettingsRepository
import com.sscrobbler.app.util.Clock
import com.sscrobbler.app.util.DebugLog
import com.sscrobbler.app.util.HashUtils
import com.sscrobbler.app.util.SystemClockImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class FinalizeReason {
    TrackChanged,
    Stopped,
    SessionDestroyed,
    NaturalEnd,
    PauseTimeout,
    TrackRepeated
}

class ScrobbleEngine(
    private val pendingScrobbleDao: PendingScrobbleDao,
    private val historyDao: HistoryDao,
    private val settingsRepository: SettingsRepository,
    private val scheduleUpload: () -> Unit,
    private val clock: Clock = SystemClockImpl
) {
    var currentSession: PlaybackSession? = null
        private set

    private var lastKnownPositionMs: Long? = null
    private var pausedAtElapsedMs: Long? = null

    private val _statusFlow = MutableStateFlow<ScrobbleStatus>(ScrobbleStatus.Listening)
    val statusFlow: StateFlow<ScrobbleStatus> = _statusFlow.asStateFlow()

    private val _currentSessionFlow = MutableStateFlow<PlaybackSession?>(null)
    val currentSessionFlow: StateFlow<PlaybackSession?> = _currentSessionFlow.asStateFlow()

    suspend fun onTrackStarted(track: Track) {
        val settings = settingsRepository.getSettings()

        // Check package filter
        if (!(settings.packageFilter[track.sourcePackage] ?: settings.defaultNewAppsAllowed)) {
            DebugLog.log("ENGINE", "IGNORED ${track.artist} - ${track.title} (app ${track.sourcePackage} disabled)")
            finalizeCurrentSession(FinalizeReason.TrackChanged)
            return
        }

        // Finalize previous session if exists
        if (currentSession != null) {
            finalizeCurrentSession(FinalizeReason.TrackChanged)
        }

        val session = PlaybackSession(
            track = track,
            startedAtUnix = clock.currentTimeMillis() / 1000,
            listenedMs = 0L,
            lastPlayStartedElapsedMs = clock.elapsedRealtime(),
            eligible = false
        )
        currentSession = session
        _currentSessionFlow.value = session
        lastKnownPositionMs = 0L
        pausedAtElapsedMs = null
        _statusFlow.value = ScrobbleStatus.Listening
        DebugLog.log(
            "ENGINE",
            "START ${track.artist} - ${track.title}" +
                (track.durationMs?.let { " (${it / 1000}s)" } ?: " (duration unknown)")
        )
    }

    fun updateTrackMetadata(track: Track) {
        val session = currentSession ?: return
        currentSession = session.copy(track = track)
        _currentSessionFlow.value = currentSession
    }

    suspend fun onPlaybackStateChanged(isPlaying: Boolean) {
        val session = currentSession ?: return
        if (isPlaying) {
            session.onPlay(clock)
            pausedAtElapsedMs = null
            checkEligibility()
            _currentSessionFlow.value = session
            _statusFlow.value = if (session.eligible) ScrobbleStatus.WaitingForEnd else ScrobbleStatus.Listening
            DebugLog.log("ENGINE", "PLAY ${session.track.title} @ ${session.totalListenedMs(clock) / 1000}s listened")
        } else {
            session.onPauseOrStop(clock)
            pausedAtElapsedMs = clock.elapsedRealtime()
            checkEligibility()
            _currentSessionFlow.value = session
            _statusFlow.value = if (session.eligible) ScrobbleStatus.Eligible else ScrobbleStatus.Paused
            DebugLog.log("ENGINE", "PAUSE ${session.track.title} @ ${session.listenedMs / 1000}s listened, eligible=${session.eligible}")
        }
    }

    suspend fun onPositionChanged(positionMs: Long) {
        val session = currentSession ?: return
        val track = session.track
        val duration = track.durationMs

        // Check if track repeated (Track A -> Track A again)
        if (duration != null && duration > 0) {
            val prevPos = lastKnownPositionMs ?: 0L
            if (prevPos > (duration * 0.70) && positionMs < 5_000L) {
                // Natural repeat of the same track
                finalizeCurrentSession(FinalizeReason.TrackRepeated)
                onTrackStarted(track)
                return
            }

            // Natural end detection
            if (positionMs >= (duration - 3_000L) && !session.isPlaying) {
                finalizeCurrentSession(FinalizeReason.NaturalEnd)
                return
            }
        }

        lastKnownPositionMs = positionMs
    }

    suspend fun onPlaybackStopped() {
        finalizeCurrentSession(FinalizeReason.Stopped)
    }

    suspend fun onSessionDestroyed() {
        finalizeCurrentSession(FinalizeReason.SessionDestroyed)
    }

    suspend fun onTimeTick() {
        val session = currentSession ?: return
        val settings = settingsRepository.getSettings()

        if (session.isPlaying) {
            checkEligibility(settings)
            _currentSessionFlow.value = session
            _statusFlow.value = if (session.eligible) ScrobbleStatus.WaitingForEnd else ScrobbleStatus.Listening
        } else {
            // Check pause timeout
            pausedAtElapsedMs?.let { pausedAt ->
                val pausedDuration = clock.elapsedRealtime() - pausedAt
                if (settings.pauseTimeoutMs > 0 && pausedDuration >= settings.pauseTimeoutMs) {
                    finalizeCurrentSession(FinalizeReason.PauseTimeout)
                }
            }
        }
    }

    private suspend fun checkEligibility(settings: AppSettings? = null) {
        val session = currentSession ?: return
        val currentSettings = settings ?: settingsRepository.getSettings()
        val duration = session.track.durationMs

        // If duration is too short, track cannot be eligible
        if (duration != null && duration < currentSettings.minTrackDurationMs) {
            session.eligible = false
            return
        }

        val listened = session.totalListenedMs(clock)
        val threshold = calculateThreshold(
            durationMs = duration,
            minListenedPercent = currentSettings.minListenedPercent,
            maxRequiredTimeMs = currentSettings.maxRequiredTimeMs
        )

        session.eligible = listened >= threshold
    }

    suspend fun finalizeCurrentSession(reason: FinalizeReason = FinalizeReason.TrackChanged) {
        val session = currentSession ?: return

        // Accrue any remaining play time
        session.onPauseOrStop(clock)
        checkEligibility()

        val track = session.track
        val recordId = UUID.randomUUID().toString()
        val timestamp = session.startedAtUnix
        val durationSec = track.durationMs?.let { (it / 1000).toInt() }
        val listenedSec = (session.listenedMs / 1000).toInt()
        val currentSettings = settingsRepository.getSettings()
        val thresholdSec = calculateThreshold(
            durationMs = track.durationMs,
            minListenedPercent = currentSettings.minListenedPercent,
            maxRequiredTimeMs = currentSettings.maxRequiredTimeMs
        ) / 1000

        DebugLog.log(
            "ENGINE",
            "FINISH($reason) ${track.artist} - ${track.title} | listened ${listenedSec}s / need ${thresholdSec}s | eligible=${session.eligible}"
        )

        val status = if (session.eligible) ScrobbleStatus.Pending else ScrobbleStatus.Skipped
        val history = HistoryItemEntity(
            id = recordId,
            artist = track.artist,
            title = track.title,
            album = track.album,
            durationSeconds = durationSec,
            listenedSeconds = listenedSec,
            timestamp = timestamp,
            status = status,
            sourcePackage = track.sourcePackage
        )
        if (session.eligible) {
            val entry = PendingScrobbleEntity(
                id = recordId,
                artist = track.artist,
                title = track.title,
                album = track.album,
                albumArtist = track.albumArtist,
                durationSeconds = durationSec,
                timestamp = timestamp,
                sourcePackage = track.sourcePackage,
                createdAt = clock.currentTimeMillis(),
                fingerprint = HashUtils.sha256("${track.artist}|${track.title}|$timestamp")
            )
            pendingScrobbleDao.enqueue(entry, history)
            scheduleUpload()
        } else {
            historyDao.insert(history)
        }

        _statusFlow.value = status
        currentSession = null
        _currentSessionFlow.value = null
        lastKnownPositionMs = null
        pausedAtElapsedMs = null
    }

    companion object {
        fun calculateThreshold(
            durationMs: Long?,
            minListenedPercent: Int = 50,
            maxRequiredTimeMs: Long = 240_000L
        ): Long {
            val duration = durationMs ?: maxRequiredTimeMs
            val percentThreshold = (duration * minListenedPercent) / 100L
            return minOf(percentThreshold, maxRequiredTimeMs)
        }
    }
}
