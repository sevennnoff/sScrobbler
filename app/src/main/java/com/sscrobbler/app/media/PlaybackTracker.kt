package com.sscrobbler.app.media

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.lastfm.LastFmClient
import com.sscrobbler.app.model.Track
import com.sscrobbler.app.scrobble.FinalizeReason
import com.sscrobbler.app.scrobble.ScrobbleEngine
import com.sscrobbler.app.settings.SettingsRepository
import com.sscrobbler.app.util.Clock
import com.sscrobbler.app.util.NetworkDetector
import com.sscrobbler.app.util.SystemClockImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface MediaControllerAdapter {
    val packageName: String
    val playbackState: PlaybackState?
    val metadata: MediaMetadata?
    fun registerCallback(callback: MediaController.Callback)
    fun unregisterCallback(callback: MediaController.Callback)
}

class SystemMediaControllerAdapter(
    private val controller: MediaController,
    private val handler: Handler = Handler(Looper.getMainLooper())
) : MediaControllerAdapter {
    override val packageName: String
        get() = controller.packageName

    override val playbackState: PlaybackState?
        get() = try { controller.playbackState } catch (e: Exception) { null }

    override val metadata: MediaMetadata?
        get() = try { controller.metadata } catch (e: Exception) { null }

    override fun registerCallback(callback: MediaController.Callback) {
        try {
            controller.registerCallback(callback, handler)
        } catch (e: Exception) {
            // Guard against dead remote binder or looper issues
        }
    }

    override fun unregisterCallback(callback: MediaController.Callback) {
        try {
            controller.unregisterCallback(callback)
        } catch (e: Exception) {
            // Guard against dead remote binder
        }
    }
}

class PlaybackTracker(
    private val scrobbleEngine: ScrobbleEngine,
    private val settingsRepository: SettingsRepository,
    private val authRepository: LastFmAuthRepository,
    private val lastFmClient: LastFmClient,
    private val networkDetector: NetworkDetector,
    private val clock: Clock = SystemClockImpl,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    inner class ControllerSession(
        val controller: MediaControllerAdapter
    ) : MediaController.Callback() {
        var lastPlaybackState: PlaybackState? = null
        var lastMetadata: MediaMetadata? = null
        var lastKnownPositionMs: Long = 0L
        var lastStateChangeElapsedMs: Long = clock.elapsedRealtime()

        fun isPlaying(): Boolean {
            val state = lastPlaybackState?.state ?: PlaybackState.STATE_NONE
            return state == PlaybackState.STATE_PLAYING ||
                    state == PlaybackState.STATE_FAST_FORWARDING ||
                    state == PlaybackState.STATE_REWINDING
        }

        fun currentPosition(): Long {
            val state = lastPlaybackState ?: return 0L
            val rawPos = state.position
            if (rawPos < 0) return 0L
            val stateTime = state.lastPositionUpdateTime
            if (state.state == PlaybackState.STATE_PLAYING && stateTime > 0) {
                val now = clock.elapsedRealtime()
                val speed = state.playbackSpeed
                val diff = (now - stateTime) * (if (speed > 0) speed else 1.0f)
                return (rawPos + diff).toLong()
            }
            return rawPos
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            lastPlaybackState = state
            lastStateChangeElapsedMs = clock.elapsedRealtime()
            handleStateChanged(this, state)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            lastMetadata = metadata
            handleMetadataChanged(this, metadata)
        }

        override fun onSessionDestroyed() {
            handleSessionDestroyed(this)
        }
    }

    private val trackedSessions = mutableMapOf<String, ControllerSession>()
    var activeSessionKey: String? = null
        private set

    private var activeTrack: Track? = null
    private var lastObservedPositionMs: Long = 0L
    private var nowPlayingSentForTrack: Track? = null

    private val _activeTrackFlow = MutableStateFlow<Track?>(null)
    val activeTrackFlow: StateFlow<Track?> = _activeTrackFlow.asStateFlow()

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow.asStateFlow()

    private val _positionFlow = MutableStateFlow(0L)
    val positionFlow: StateFlow<Long> = _positionFlow.asStateFlow()

    private val _artworkBitmapFlow = MutableStateFlow<Bitmap?>(null)
    val artworkBitmapFlow: StateFlow<Bitmap?> = _artworkBitmapFlow.asStateFlow()

    private val _discoveredPackagesFlow = MutableStateFlow<Set<String>>(emptySet())
    val discoveredPackagesFlow: StateFlow<Set<String>> = _discoveredPackagesFlow.asStateFlow()

    private var pauseTimeoutJob: Job? = null
    private var tickJob: Job? = null

    private var settingsJob: kotlinx.coroutines.Job? = null

    init {
        startTicker()
        settingsJob = scope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                val activeKey = activeSessionKey
                if (activeKey != null) {
                    val isAllowed = settings.packageFilter[activeKey] ?: settings.defaultNewAppsAllowed
                    if (!isAllowed) {
                        scrobbleEngine.finalizeCurrentSession(FinalizeReason.SessionDestroyed)
                        activeSessionKey = null
                        activeTrack = null
                        _activeTrackFlow.value = null
                        _isPlayingFlow.value = false
                        _positionFlow.value = 0L
                        _artworkBitmapFlow.value = null
                        cancelPauseTimeout()
                    }
                }
            }
        }
    }

    fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(1000L)
                onTick()
            }
        }
    }

    fun stopTicker() {
        tickJob?.cancel()
        tickJob = null
    }

    suspend fun onTick() {
        if (activeSessionKey == null || trackedSessions[activeSessionKey] == null) {
            MediaNotificationListener.instance?.refreshActiveSessions()
            arbitrateActiveSession()
        }
        val activeKey = activeSessionKey ?: return
        val session = trackedSessions[activeKey] ?: return

        // Proactively check if player switched track without emitting onMetadataChanged
        val liveMeta = session.controller.metadata
        val liveTrack = extractTrack(liveMeta, activeKey)
        if (liveTrack != null && liveTrack != activeTrack) {
            handleMetadataChanged(session, liveMeta)
            return
        }

        scrobbleEngine.onTimeTick()

        if (session.isPlaying()) {
            val pos = session.currentPosition()
            val duration = activeTrack?.durationMs ?: 0L
            if (duration > 0 && lastObservedPositionMs > (duration * 0.70) && pos < 5000L) {
                nowPlayingSentForTrack = null
            }
            session.lastKnownPositionMs = pos
            lastObservedPositionMs = pos
            _positionFlow.value = pos
            _isPlayingFlow.value = true
            scrobbleEngine.onPositionChanged(pos)
        } else {
            val pos = session.currentPosition()
            _positionFlow.value = pos
            _isPlayingFlow.value = false
            val duration = activeTrack?.durationMs ?: 0L
            if (duration > 0 && pos >= (duration - 3000L)) {
                scrobbleEngine.finalizeCurrentSession(FinalizeReason.NaturalEnd)
                activeSessionKey = null
                activeTrack = null
                _activeTrackFlow.value = null
                _isPlayingFlow.value = false
                _positionFlow.value = 0L
                _artworkBitmapFlow.value = null
                cancelPauseTimeout()
            }
        }
    }

    fun onActiveSessionsChanged(controllers: List<MediaControllerAdapter>) {
        _discoveredPackagesFlow.value = _discoveredPackagesFlow.value + controllers.map { it.packageName }.toSet()
        scope.launch {
            val settings = settingsRepository.getSettings()
            val allowedControllers = controllers.filter { controller ->
                settings.packageFilter[controller.packageName] ?: settings.defaultNewAppsAllowed
            }

            val newKeys = allowedControllers.map { it.packageName }.toSet()

            val iterator = trackedSessions.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in newKeys) {
                    entry.value.controller.unregisterCallback(entry.value)
                    if (entry.key == activeSessionKey) {
                        scrobbleEngine.finalizeCurrentSession(FinalizeReason.SessionDestroyed)
                        activeSessionKey = null
                        activeTrack = null
                        cancelPauseTimeout()
                    }
                    iterator.remove()
                }
            }

            for (controller in allowedControllers) {
                val pkg = controller.packageName
                if (!trackedSessions.containsKey(pkg)) {
                    val session = ControllerSession(controller)
                    session.lastPlaybackState = controller.playbackState
                    session.lastMetadata = controller.metadata
                    controller.registerCallback(session)
                    trackedSessions[pkg] = session
                }
            }

            arbitrateActiveSession()
        }
    }

    fun registerController(controller: MediaControllerAdapter) {
        val pkg = controller.packageName
        _discoveredPackagesFlow.value = _discoveredPackagesFlow.value + pkg
        if (trackedSessions.containsKey(pkg)) return

        val session = ControllerSession(controller)
        session.lastPlaybackState = controller.playbackState
        session.lastMetadata = controller.metadata
        controller.registerCallback(session)
        trackedSessions[pkg] = session

        scope.launch {
            arbitrateActiveSession()
        }
    }

    fun unregisterController(pkg: String) {
        val session = trackedSessions.remove(pkg) ?: return
        session.controller.unregisterCallback(session)
        if (pkg == activeSessionKey) {
            scope.launch {
                scrobbleEngine.finalizeCurrentSession(FinalizeReason.SessionDestroyed)
                activeSessionKey = null
                activeTrack = null
                _activeTrackFlow.value = null
                _isPlayingFlow.value = false
                _positionFlow.value = 0L
                _artworkBitmapFlow.value = null
                cancelPauseTimeout()
                arbitrateActiveSession()
            }
        }
    }

    private fun handleStateChanged(session: ControllerSession, state: PlaybackState?) {
        scope.launch {
            val pkg = session.controller.packageName
            val isPlaying = session.isPlaying()

            if (isPlaying && activeSessionKey != pkg) {
                switchActiveSession(pkg)
                return@launch
            }

            if (activeSessionKey == pkg) {
                val currentTrack = activeTrack
                val pos = session.currentPosition()
                val duration = currentTrack?.durationMs ?: 0L

                _isPlayingFlow.value = isPlaying
                _positionFlow.value = pos

                // Repeat detection on state change:
                // old position > 70% and new position < 5s while playing
                if (duration > 0 && lastObservedPositionMs > (duration * 0.70) && pos < 5000L && isPlaying) {
                    scrobbleEngine.finalizeCurrentSession(FinalizeReason.TrackRepeated)
                    currentTrack?.let {
                        scrobbleEngine.onTrackStarted(it)
                        triggerNowPlaying(it)
                    }
                    lastObservedPositionMs = pos
                    session.lastKnownPositionMs = pos
                    return@launch
                }

                // Check natural end
                if (duration > 0 && pos >= (duration - 3000L) && !isPlaying) {
                    scrobbleEngine.finalizeCurrentSession(FinalizeReason.NaturalEnd)
                    activeSessionKey = null
                    activeTrack = null
                    _activeTrackFlow.value = null
                    _isPlayingFlow.value = false
                    _positionFlow.value = 0L
                    _artworkBitmapFlow.value = null
                    cancelPauseTimeout()
                    return@launch
                }

                scrobbleEngine.onPlaybackStateChanged(isPlaying)

                if (isPlaying) {
                    cancelPauseTimeout()
                    currentTrack?.let { triggerNowPlaying(it) }
                } else {
                    schedulePauseTimeout()
                    clearNowPlaying(currentTrack)
                }

                lastObservedPositionMs = pos
                session.lastKnownPositionMs = pos
            } else {
                arbitrateActiveSession()
            }
        }
    }

    private fun handleMetadataChanged(session: ControllerSession, metadata: MediaMetadata?) {
        scope.launch {
            val pkg = session.controller.packageName
            val newTrack = extractTrack(metadata, pkg)

            if (activeSessionKey == pkg) {
                if (newTrack != null && newTrack != activeTrack) {
                    scrobbleEngine.finalizeCurrentSession(FinalizeReason.TrackChanged)
                    activeTrack = newTrack
                    _activeTrackFlow.value = newTrack
                    _artworkBitmapFlow.value = extractArtwork(metadata)
                    lastObservedPositionMs = 0L
                    session.lastKnownPositionMs = 0L
                    _positionFlow.value = 0L
                    cancelPauseTimeout()
                    scrobbleEngine.onTrackStarted(newTrack)
                    if (session.isPlaying()) {
                        _isPlayingFlow.value = true
                        scrobbleEngine.onPlaybackStateChanged(true)
                        triggerNowPlaying(newTrack)
                    }
                } else if (newTrack != null && newTrack == activeTrack) {
                    _artworkBitmapFlow.value = extractArtwork(metadata)
                    val pos = session.currentPosition()
                    val duration = newTrack.durationMs ?: 0L
                    if (duration > 0 && lastObservedPositionMs > (duration * 0.70) && pos < 5000L) {
                        scrobbleEngine.finalizeCurrentSession(FinalizeReason.TrackRepeated)
                        scrobbleEngine.onTrackStarted(newTrack)
                        triggerNowPlaying(newTrack)
                        lastObservedPositionMs = pos
                        session.lastKnownPositionMs = pos
                        _positionFlow.value = pos
                    }
                }
            } else if (session.isPlaying()) {
                switchActiveSession(pkg)
            }
        }
    }

    private fun handleSessionDestroyed(session: ControllerSession) {
        val pkg = session.controller.packageName
        unregisterController(pkg)
    }

    private suspend fun arbitrateActiveSession() {
        val playingSessions = trackedSessions.values.filter { it.isPlaying() }
        val candidate = if (playingSessions.isNotEmpty()) {
            playingSessions.maxByOrNull { it.lastStateChangeElapsedMs }
        } else {
            activeSessionKey?.let { trackedSessions[it] }
        }

        val targetKey = candidate?.controller?.packageName
        if (targetKey != null && targetKey != activeSessionKey) {
            switchActiveSession(targetKey)
        }
    }

    private suspend fun switchActiveSession(newKey: String) {
        val newSession = trackedSessions[newKey] ?: return

        if (activeSessionKey != null && activeSessionKey != newKey) {
            scrobbleEngine.finalizeCurrentSession(FinalizeReason.TrackChanged)
            cancelPauseTimeout()
        }

        activeSessionKey = newKey
        val track = extractTrack(newSession.lastMetadata, newKey)
        activeTrack = track
        _activeTrackFlow.value = track
        _artworkBitmapFlow.value = extractArtwork(newSession.lastMetadata)

        if (track != null) {
            scrobbleEngine.onTrackStarted(track)
            val isPlaying = newSession.isPlaying()
            _isPlayingFlow.value = isPlaying
            scrobbleEngine.onPlaybackStateChanged(isPlaying)
            if (isPlaying) {
                cancelPauseTimeout()
                triggerNowPlaying(track)
            } else {
                schedulePauseTimeout()
            }
            lastObservedPositionMs = newSession.currentPosition()
            newSession.lastKnownPositionMs = lastObservedPositionMs
            _positionFlow.value = lastObservedPositionMs
        } else {
            _isPlayingFlow.value = false
            _positionFlow.value = 0L
        }
    }

    private fun schedulePauseTimeout() {
        cancelPauseTimeout()
        pauseTimeoutJob = scope.launch {
            val settings = settingsRepository.getSettings()
            val timeoutMs = settings.pauseTimeoutMs
            if (timeoutMs > 0L) {
                delay(timeoutMs)
                scrobbleEngine.finalizeCurrentSession(FinalizeReason.PauseTimeout)
                activeSessionKey = null
                activeTrack = null
                _activeTrackFlow.value = null
                _isPlayingFlow.value = false
                _positionFlow.value = 0L
                _artworkBitmapFlow.value = null
            }
        }
    }

    private fun cancelPauseTimeout() {
        pauseTimeoutJob?.cancel()
        pauseTimeoutJob = null
    }

    private suspend fun clearNowPlaying(track: Track?) {
        nowPlayingSentForTrack = null
        if (track == null) return
        val sessionKey = authRepository.getSessionKey() ?: return
        if (sessionKey.isNotBlank() && networkDetector.isOnline()) {
            lastFmClient.updateNowPlaying(
                artist = track.artist,
                track = track.title,
                durationSeconds = 1,
                sessionKey = sessionKey
            )
        }
    }

    private suspend fun triggerNowPlaying(track: Track) {
        val settings = settingsRepository.getSettings()
        if (!settings.sendNowPlaying) return
        if (nowPlayingSentForTrack == track) return

        val sessionKey = authRepository.getSessionKey()
        if (!sessionKey.isNullOrBlank() && networkDetector.isOnline()) {
            val durationSec = track.durationMs?.let { (it / 1000).toInt() }
            val result = lastFmClient.updateNowPlaying(
                artist = track.artist,
                track = track.title,
                album = track.album,
                albumArtist = track.albumArtist,
                durationSeconds = durationSec,
                sessionKey = sessionKey
            )
            if (result is com.sscrobbler.app.lastfm.LastFmResult.Success) {
                nowPlayingSentForTrack = track
            }
        }
    }

    fun extractTrack(metadata: MediaMetadata?, sourcePackage: String): Track? {
        if (metadata == null) return null
        var artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_COMPOSER)
            ?: metadata.description?.subtitle?.toString()

        var title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata.description?.title?.toString()

        if (artist.isNullOrBlank() && !title.isNullOrBlank() && title.contains(" - ")) {
            val parts = title.split(" - ", limit = 2)
            artist = parts[0].trim()
            title = parts[1].trim()
        }

        if (artist.isNullOrBlank() || title.isNullOrBlank()) {
            return null
        }

        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?: metadata.description?.description?.toString()
        val albumArtist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).let {
            if (it > 0) it else null
        }

        return Track(
            artist = artist.trim(),
            title = title.trim(),
            album = album?.trim(),
            albumArtist = albumArtist?.trim(),
            durationMs = durationMs,
            sourcePackage = sourcePackage
        )
    }

    fun extractArtwork(metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null
        return try {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.description?.iconBitmap
        } catch (e: Throwable) {
            null
        }
    }

    fun destroy() {
        stopTicker()
        settingsJob?.cancel()
        settingsJob = null
        cancelPauseTimeout()
        trackedSessions.values.forEach { it.controller.unregisterCallback(it) }
        trackedSessions.clear()
    }
}
