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
import com.sscrobbler.app.util.DebugLog
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private var lastNowPlayingTrack: Track? = null
    private var lastNowPlayingSentElapsedMs: Long = 0L
    private var nowPlayingJob: Job? = null
    private val nowPlayingMutex = Mutex()

    private val _activeTrackFlow = MutableStateFlow<Track?>(null)
    val activeTrackFlow: StateFlow<Track?> = _activeTrackFlow.asStateFlow()

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow.asStateFlow()

    private val _positionFlow = MutableStateFlow(0L)
    val positionFlow: StateFlow<Long> = _positionFlow.asStateFlow()

    private val _artworkBitmapFlow = MutableStateFlow<Bitmap?>(null)
    val artworkBitmapFlow: StateFlow<Bitmap?> = _artworkBitmapFlow.asStateFlow()

    private val _artworkUriFlow = MutableStateFlow<String?>(null)
    val artworkUriFlow: StateFlow<String?> = _artworkUriFlow.asStateFlow()

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
                        _artworkUriFlow.value = null
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
        if (liveTrack != null && !isSameTrack(liveTrack, activeTrack)) {
            handleMetadataChanged(session, liveMeta)
            return
        }

        scrobbleEngine.onTimeTick()

        if (session.isPlaying()) {
            val pos = session.currentPosition()
            val duration = activeTrack?.durationMs ?: 0L
            if (duration > 0 && lastObservedPositionMs > (duration * 0.70) && pos < 5000L) {
                lastNowPlayingTrack = null
                lastNowPlayingSentElapsedMs = 0L
            }
            session.lastKnownPositionMs = pos
            lastObservedPositionMs = pos
            _positionFlow.value = pos
            _isPlayingFlow.value = true
            scrobbleEngine.onPositionChanged(pos)

            // Periodically refresh Now Playing for long tracks (Last.fm server TTL is ~3-4 minutes)
            val current = activeTrack
            if (current != null) {
                val now = clock.elapsedRealtime()
                if (now - lastNowPlayingSentElapsedMs >= 150_000L) {
                    triggerNowPlaying(current, force = true)
                }
            }
        } else {
            val pos = session.currentPosition()
            _positionFlow.value = pos
            _isPlayingFlow.value = false
            val duration = activeTrack?.durationMs ?: 0L
            if (duration > 0 && pos >= (duration - 3000L)) {
                scrobbleEngine.finalizeCurrentSession(FinalizeReason.NaturalEnd)
                activeSessionKey = null
                activeTrack = null
                lastNowPlayingTrack = null
                lastNowPlayingSentElapsedMs = 0L
                nowPlayingJob?.cancel()
                _activeTrackFlow.value = null
                _isPlayingFlow.value = false
                _positionFlow.value = 0L
                _artworkBitmapFlow.value = null
                _artworkUriFlow.value = null
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
                        lastNowPlayingTrack = null
                        lastNowPlayingSentElapsedMs = 0L
                        nowPlayingJob?.cancel()
                        _activeTrackFlow.value = null
                        _isPlayingFlow.value = false
                        _positionFlow.value = 0L
                        _artworkBitmapFlow.value = null
                        _artworkUriFlow.value = null
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
                lastNowPlayingTrack = null
                lastNowPlayingSentElapsedMs = 0L
                nowPlayingJob?.cancel()
                _activeTrackFlow.value = null
                _isPlayingFlow.value = false
                _positionFlow.value = 0L
                _artworkBitmapFlow.value = null
                _artworkUriFlow.value = null
                cancelPauseTimeout()
                arbitrateActiveSession()
            }
        }
    }

    private fun handleStateChanged(session: ControllerSession, state: PlaybackState?) {
        scope.launch {
            val pkg = session.controller.packageName
            val isPlaying = session.isPlaying()
            val stateCode = state?.state ?: PlaybackState.STATE_NONE
            val isStopped = stateCode == PlaybackState.STATE_STOPPED || stateCode == PlaybackState.STATE_NONE
            DebugLog.log(
                "TRACK",
                "state=$stateCode playing=$isPlaying pkg=${pkg.substringAfterLast('.')} pos=${session.currentPosition() / 1000}s track=${activeTrack?.title ?: "none"}"
            )

            if (isPlaying && activeSessionKey != pkg) {
                switchActiveSession(pkg)
                return@launch
            }

            if (activeSessionKey == pkg) {
                if (isStopped) {
                    scrobbleEngine.onPlaybackStopped()
                    activeSessionKey = null
                    activeTrack = null
                    lastNowPlayingTrack = null
                    lastNowPlayingSentElapsedMs = 0L
                    nowPlayingJob?.cancel()
                    _activeTrackFlow.value = null
                    _isPlayingFlow.value = false
                    _positionFlow.value = 0L
                    _artworkBitmapFlow.value = null
                    _artworkUriFlow.value = null
                    cancelPauseTimeout()
                    return@launch
                }

                // If controller metadata is ahead of activeTrack, route to metadata changed first
                val liveMeta = session.controller.metadata ?: session.lastMetadata
                val liveTrack = extractTrack(liveMeta, pkg)
                if (liveTrack != null && !isSameTrack(liveTrack, activeTrack)) {
                    handleMetadataChanged(session, liveMeta)
                    return@launch
                }

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
                        lastNowPlayingTrack = null
                        lastNowPlayingSentElapsedMs = 0L
                        scrobbleEngine.onTrackStarted(it)
                        triggerNowPlaying(it, force = true)
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
                    lastNowPlayingTrack = null
                    lastNowPlayingSentElapsedMs = 0L
                    nowPlayingJob?.cancel()
                    _activeTrackFlow.value = null
                    _isPlayingFlow.value = false
                    _positionFlow.value = 0L
                    _artworkBitmapFlow.value = null
                    _artworkUriFlow.value = null
                    cancelPauseTimeout()
                    return@launch
                }

                scrobbleEngine.onPlaybackStateChanged(isPlaying)

                if (isPlaying) {
                    cancelPauseTimeout()
                    if (currentTrack != null) {
                        triggerNowPlaying(currentTrack, force = true)
                    }
                } else {
                    schedulePauseTimeout()
                    nowPlayingJob?.cancel()
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
            DebugLog.log(
                "TRACK",
                "metadata pkg=${pkg.substringAfterLast('.')} -> ${newTrack?.artist ?: "?"} - ${newTrack?.title ?: "?"}" +
                    (newTrack?.durationMs?.let { " (${it / 1000}s)" } ?: " (no duration)")
            )

            if (activeSessionKey == pkg) {
                if (newTrack != null && !isSameTrack(newTrack, activeTrack)) {
                    nowPlayingJob?.cancel()
                    lastNowPlayingTrack = null
                    lastNowPlayingSentElapsedMs = 0L
                    val newBitmap = extractArtwork(metadata)
                    val newUri = extractArtworkUri(metadata)
                    activeTrack = newTrack
                    _activeTrackFlow.value = newTrack
                    _artworkBitmapFlow.value = newBitmap
                    _artworkUriFlow.value = newUri
                    lastObservedPositionMs = 0L
                    session.lastKnownPositionMs = 0L
                    _positionFlow.value = 0L
                    cancelPauseTimeout()
                    scrobbleEngine.finalizeCurrentSession(FinalizeReason.TrackChanged)
                    scrobbleEngine.onTrackStarted(newTrack)
                    if (session.isPlaying()) {
                        _isPlayingFlow.value = true
                        scrobbleEngine.onPlaybackStateChanged(true)
                        triggerNowPlaying(newTrack, force = true)
                    }
                } else if (newTrack != null && isSameTrack(newTrack, activeTrack)) {
                    val newBitmap = extractArtwork(metadata)
                    if (newBitmap != null) {
                        _artworkBitmapFlow.value = newBitmap
                    }
                    val newUri = extractArtworkUri(metadata)
                    if (!newUri.isNullOrBlank()) {
                        _artworkUriFlow.value = newUri
                    }
                    if (newTrack.durationMs != null && activeTrack?.durationMs == null) {
                        activeTrack = activeTrack?.copy(durationMs = newTrack.durationMs)
                        _activeTrackFlow.value = activeTrack
                    }
                    if (newTrack.album != null && activeTrack?.album == null) {
                        activeTrack = activeTrack?.copy(album = newTrack.album)
                        _activeTrackFlow.value = activeTrack
                    }
                    val pos = session.currentPosition()
                    val duration = newTrack.durationMs ?: activeTrack?.durationMs ?: 0L
                    if (duration > 0 && lastObservedPositionMs > (duration * 0.70) && pos < 5000L) {
                        scrobbleEngine.finalizeCurrentSession(FinalizeReason.TrackRepeated)
                        lastNowPlayingTrack = null
                        lastNowPlayingSentElapsedMs = 0L
                        scrobbleEngine.onTrackStarted(newTrack)
                        triggerNowPlaying(newTrack, force = true)
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
        DebugLog.log("TRACK", "active session -> ${newKey.substringAfterLast('.')}")

        if (activeSessionKey != null && activeSessionKey != newKey) {
            scrobbleEngine.finalizeCurrentSession(FinalizeReason.TrackChanged)
            cancelPauseTimeout()
        }

        activeSessionKey = newKey
        val meta = newSession.controller.metadata ?: newSession.lastMetadata
        newSession.lastMetadata = meta
        val track = extractTrack(meta, newKey)
        nowPlayingJob?.cancel()
        lastNowPlayingTrack = null
        lastNowPlayingSentElapsedMs = 0L
        activeTrack = track
        _activeTrackFlow.value = track
        _artworkBitmapFlow.value = extractArtwork(meta)
        _artworkUriFlow.value = extractArtworkUri(meta)

        if (track != null) {
            scrobbleEngine.onTrackStarted(track)
            val isPlaying = newSession.isPlaying()
            _isPlayingFlow.value = isPlaying
            scrobbleEngine.onPlaybackStateChanged(isPlaying)
            if (isPlaying) {
                cancelPauseTimeout()
                triggerNowPlaying(track, force = true)
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
                lastNowPlayingTrack = null
                lastNowPlayingSentElapsedMs = 0L
                nowPlayingJob?.cancel()
                _activeTrackFlow.value = null
                _isPlayingFlow.value = false
                _positionFlow.value = 0L
                _artworkBitmapFlow.value = null
                _artworkUriFlow.value = null
            }
        }
    }

    private fun cancelPauseTimeout() {
        pauseTimeoutJob?.cancel()
        pauseTimeoutJob = null
    }

    private fun isSameTrack(t1: Track?, t2: Track?): Boolean {
        if (t1 == null && t2 == null) return true
        if (t1 == null || t2 == null) return false
        return t1.artist.trim().equals(t2.artist.trim(), ignoreCase = true) &&
                t1.title.trim().equals(t2.title.trim(), ignoreCase = true)
    }

    private fun triggerNowPlaying(track: Track, force: Boolean = false) {
        val active = activeTrack ?: return
        if (!isSameTrack(track, active)) return

        val now = clock.elapsedRealtime()
        val isDifferentTrack = !isSameTrack(lastNowPlayingTrack, track)

        // Throttle rapid duplicate calls for the same track
        if (!isDifferentTrack && !force && (now - lastNowPlayingSentElapsedMs < 30_000L)) {
            return
        }

        // Even with force (resume from pause / periodic tick), avoid spamming if sent in the last 10 seconds
        if (!isDifferentTrack && (now - lastNowPlayingSentElapsedMs < 10_000L)) {
            return
        }

        nowPlayingJob?.cancel()
        nowPlayingJob = scope.launch {
            val settings = settingsRepository.getSettings()
            if (!settings.sendNowPlaying) return@launch
            val current = activeTrack ?: return@launch
            if (!isSameTrack(track, current)) return@launch

            val sessionKey = authRepository.getSessionKey()
            if (!sessionKey.isNullOrBlank() && networkDetector.isOnline()) {
                val durationSec = track.durationMs?.let { (it / 1000).toInt() }
                DebugLog.log(
                    "NP",
                    "SEND ${track.artist} - ${track.title}" + if (force) " (forced)" else ""
                )
                val result = nowPlayingMutex.withLock {
                    lastFmClient.updateNowPlaying(
                        artist = track.artist,
                        track = track.title,
                        album = track.album,
                        albumArtist = track.albumArtist,
                        durationSeconds = durationSec,
                        sessionKey = sessionKey
                    )
                }
                if (result is com.sscrobbler.app.lastfm.LastFmResult.Success) {
                    val afterCall = activeTrack
                    if (afterCall != null && isSameTrack(afterCall, track)) {
                        lastNowPlayingTrack = track
                        lastNowPlayingSentElapsedMs = clock.elapsedRealtime()
                    }
                }
            } else {
                DebugLog.log(
                    "NP",
                    "SKIP ${track.artist} - ${track.title}: " +
                        if (sessionKey.isNullOrBlank()) "no session key" else "offline"
                )
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

    fun extractArtworkUri(metadata: MediaMetadata?): String? {
        if (metadata == null) return null
        return try {
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                ?: metadata.description?.iconUri?.toString()
        } catch (e: Throwable) {
            null
        }
    }

    fun destroy() {
        stopTicker()
        settingsJob?.cancel()
        settingsJob = null
        cancelPauseTimeout()
        nowPlayingJob?.cancel()
        nowPlayingJob = null
        lastNowPlayingTrack = null
        lastNowPlayingSentElapsedMs = 0L
        trackedSessions.values.forEach { it.controller.unregisterCallback(it) }
        trackedSessions.clear()
    }
}
