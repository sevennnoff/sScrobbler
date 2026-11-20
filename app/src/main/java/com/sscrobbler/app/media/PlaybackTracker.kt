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
import com.sscrobbler.app.settings.AppSettings
import com.sscrobbler.app.settings.SettingsRepository
import com.sscrobbler.app.util.Clock
import com.sscrobbler.app.util.DebugLog
import com.sscrobbler.app.util.NetworkDetector
import com.sscrobbler.app.util.SystemClockImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
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
    val sessionId: Any get() = this
    val playbackState: PlaybackState?
    val metadata: MediaMetadata?
    fun registerCallback(callback: MediaController.Callback)
    fun unregisterCallback(callback: MediaController.Callback)
}

class SystemMediaControllerAdapter(
    private val controller: MediaController,
    private val handler: Handler = Handler(Looper.getMainLooper())
) : MediaControllerAdapter {
    override val sessionId: Any get() = controller.sessionToken
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
    private inner class ControllerSession(val controller: MediaControllerAdapter) : MediaController.Callback() {
        var state = controller.playbackState
        var metadata = controller.metadata
        var lastStateChangeElapsedMs = clock.elapsedRealtime()
        var metadataStartElapsedMs: Long? = null

        fun isPlaying(): Boolean = state?.state == PlaybackState.STATE_PLAYING

        fun position(): Long {
            val now = clock.elapsedRealtime()
            metadataStartElapsedMs?.let { return if (isPlaying()) (now - it).coerceAtLeast(0L) else 0L }
            val playback = state ?: return 0L
            if (playback.position < 0L) return 0L
            val since = playback.lastPositionUpdateTime
            val elapsed = if (isPlaying() && since > 0) (now - since).coerceAtLeast(0L) else 0L
            return playback.position + (elapsed * playback.playbackSpeed.coerceAtLeast(0f)).toLong()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val liveMetadata = controller.metadata
            post {
                if (trackedSessions[controller.packageName] !== this) return@post
                val wasPlaying = isPlaying()
                this.state = state
                metadataStartElapsedMs = null
                if (wasPlaying != isPlaying()) lastStateChangeElapsedMs = clock.elapsedRealtime()
                if (liveMetadata != null) metadata = liveMetadata
                handleState(this, wasPlaying)
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            post {
                if (trackedSessions[controller.packageName] !== this) return@post
                this.metadata = metadata
                if (activeSessionKey == controller.packageName) updateMetadata(this)
                else if (isPlaying()) arbitrate()
            }
        }

        override fun onSessionDestroyed() {
            post {
                if (trackedSessions[controller.packageName] === this) {
                    latestControllers = latestControllers.filterNot { it.sessionId == controller.sessionId }
                    detach(this)
                    arbitrate()
                }
            }
        }
    }

    // Android callbacks, the ticker, and preference updates can arrive on different threads.
    // Finish each transition before consuming the next callback, including across Room suspensions.
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val trackedSessions = mutableMapOf<String, ControllerSession>()
    private var latestControllers: List<MediaControllerAdapter> = emptyList()
    private var settings = AppSettings()
    var activeSessionKey: String? = null
        private set
    private var activeTrack: Track? = null
    private var lastPosition = 0L
    private var lastRefreshMs: Long? = null
    private var lastNowPlayingAttempt: Long? = null
    private var lastNowPlayingSucceeded = false
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


    private var pausedSince: Long? = null
    private var pauseTimeoutJob: Job? = null
    private var tickJob: Job? = null
    private val settingsJob: Job

    init {
        scope.launch {
            for (event in events) {
                try {
                    event()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.log("ERROR", "Playback update failed: ${e.message}")
                }
            }
        }
        settingsJob = scope.launch {
            settingsRepository.settingsFlow.collect { updated ->
                post {
                    val filterChanged = settings.packageFilter != updated.packageFilter ||
                        settings.defaultNewAppsAllowed != updated.defaultNewAppsAllowed
                    val timeoutChanged = settings.pauseTimeoutMs != updated.pauseTimeoutMs
                    settings = updated
                    if (timeoutChanged && pausedSince != null) {
                        trackedSessions[activeSessionKey]?.let { schedulePauseTimeout(it) }
                    }
                    if (filterChanged) reconcileControllers()
                }
            }
        }
        startTicker()
    }

    private fun post(event: suspend () -> Unit) {
        events.trySend(event)
    }

    fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                onTick()
            }
        }
    }

    fun stopTicker() {
        tickJob?.cancel()
        tickJob = null
    }

    fun onActiveSessionsChanged(controllers: List<MediaControllerAdapter>) {
        post {
            latestControllers = controllers
            settings = settingsRepository.getSettings()
            reconcileControllers()
        }
    }

    fun registerController(controller: MediaControllerAdapter) {
        post {
            latestControllers = latestControllers.filterNot { it.sessionId == controller.sessionId } + controller
            settings = settingsRepository.getSettings()
            reconcileControllers()
        }
    }

    fun unregisterController(pkg: String) {
        post {
            latestControllers = latestControllers.filterNot { it.packageName == pkg }
            trackedSessions[pkg]?.let { detach(it) }
            arbitrate()
        }
    }

    private suspend fun reconcileControllers() {
        _discoveredPackagesFlow.value += latestControllers.map { it.packageName }
        val candidates = latestControllers
            .filter { settings.packageFilter[it.packageName] ?: settings.defaultNewAppsAllowed }
            .groupBy { it.packageName }
            .mapValues { (pkg, group) ->
                val playing = group.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                val preferred = playing.ifEmpty { group }
                preferred.firstOrNull { it.sessionId == trackedSessions[pkg]?.controller?.sessionId } ?: preferred.first()
            }
        for ((pkg, session) in trackedSessions.toMap()) {
            if (candidates[pkg]?.sessionId != session.controller.sessionId) detach(session)
        }
        for ((pkg, controller) in candidates) {
            if (pkg !in trackedSessions) {
                val session = ControllerSession(controller)
                trackedSessions[pkg] = session
                controller.registerCallback(session)
            }
        }
        arbitrate()
    }

    private suspend fun detach(session: ControllerSession) {
        val pkg = session.controller.packageName
        trackedSessions.remove(pkg)
        session.controller.unregisterCallback(session)
        if (activeSessionKey == pkg) finishActive(FinalizeReason.SessionDestroyed)
    }

    private suspend fun arbitrate() {
        val candidate = trackedSessions.values.filter { it.isPlaying() }.maxByOrNull { it.lastStateChangeElapsedMs }
        if (candidate != null && candidate.controller.packageName != activeSessionKey) activate(candidate)
    }

    private suspend fun activate(session: ControllerSession) {
        if (activeSessionKey != null) finishActive(FinalizeReason.TrackChanged)
        activeSessionKey = session.controller.packageName
        DebugLog.log("TRACK", "Active source: ${session.controller.packageName}")
        updateMetadata(session, pairedState = true)
    }

    private suspend fun updateMetadata(session: ControllerSession, pairedState: Boolean = false): Boolean {
        val metadata = session.metadata ?: return false
        val next = extractTrack(metadata, session.controller.packageName) ?: return false
        val previous = activeTrack
        val changed = !isSameTrack(previous, next)
        val bitmap = extractArtwork(metadata)
        val uri = extractArtworkUri(metadata)
        if (changed || bitmap != null) _artworkBitmapFlow.value = bitmap
        if (changed || uri != null) _artworkUriFlow.value = uri
        if (changed) {
            resetTimers()
            if (previous != null) scrobbleEngine.finalizeCurrentSession(FinalizeReason.TrackChanged)
            if (previous != null && !pairedState) session.metadataStartElapsedMs = clock.elapsedRealtime()
            activeTrack = next
            _activeTrackFlow.value = next
            lastPosition = session.position()
            _positionFlow.value = lastPosition
            scrobbleEngine.onTrackStarted(next)
            applyPlayback(session)
        } else if (previous != null) {
            val enriched = next.copy(
                durationMs = next.durationMs ?: previous.durationMs,
                album = next.album ?: previous.album,
                albumArtist = next.albumArtist ?: previous.albumArtist
            )
            if (previous != enriched) {
                activeTrack = enriched
                _activeTrackFlow.value = enriched
                scrobbleEngine.updateTrackMetadata(enriched)
            }
        }
        return changed
    }

    private suspend fun handleState(session: ControllerSession, wasPlaying: Boolean) {
        val pkg = session.controller.packageName
        if (pkg != activeSessionKey) {
            if (session.isPlaying()) activate(session)
            return
        }
        when (session.state?.state) {
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> {
                scrobbleEngine.onPlaybackStopped()
                clearActive()
                arbitrate()
                return
            }
        }
        if (updateMetadata(session, pairedState = true)) return
        if (activeTrack == null) return
        if (observePosition(session)) return
        if (session.isPlaying() != wasPlaying) applyPlayback(session)
        if (!session.isPlaying()) arbitrate()
    }

    private suspend fun applyPlayback(session: ControllerSession) {
        val playing = session.isPlaying()
        _isPlayingFlow.value = playing
        scrobbleEngine.onPlaybackStateChanged(playing)
        pauseTimeoutJob?.cancel()
        if (playing) {
            pausedSince = null
            activeTrack?.let { sendNowPlaying(it, force = true) }
        } else {
            nowPlayingJob?.cancel()
            pausedSince = clock.elapsedRealtime()
            schedulePauseTimeout(session)
        }
    }

    private fun schedulePauseTimeout(session: ControllerSession) {
        pauseTimeoutJob?.cancel()
        val timeout = settings.pauseTimeoutMs
        val since = pausedSince ?: return
        val track = activeTrack
        if (timeout <= 0L) return
        pauseTimeoutJob = scope.launch {
            delay((timeout - (clock.elapsedRealtime() - since)).coerceAtLeast(0L))
            post {
                if (trackedSessions[activeSessionKey] === session && isSameTrack(activeTrack, track) && pausedSince == since && !session.isPlaying()) {
                    finishActive(FinalizeReason.PauseTimeout)
                    arbitrate()
                }
            }
        }
    }

    private suspend fun observePosition(session: ControllerSession): Boolean {
        val track = activeTrack ?: return false
        val duration = track.durationMs
        val pos = session.position()
        val repeated = duration != null && lastPosition > duration * 0.70 && pos < 5_000 && session.isPlaying()
        lastPosition = pos
        _positionFlow.value = pos
        if (repeated) {
            scrobbleEngine.finalizeCurrentSession(FinalizeReason.TrackRepeated)
            resetTimers()
            scrobbleEngine.onTrackStarted(track)
            applyPlayback(session)
            return true
        }
        if (duration != null && duration >= 3_000 && pos >= duration - 3_000 && !session.isPlaying()) {
            finishActive(FinalizeReason.NaturalEnd)
            arbitrate()
            return true
        }
        return false
    }

    fun onTick() {
        post {
            if (activeSessionKey == null) {
                val now = clock.elapsedRealtime()
                val previous = lastRefreshMs
                if (previous == null || now - previous >= 15_000L) {
                    lastRefreshMs = now
                    MediaNotificationListener.instance?.refreshActiveSessions()
                }
                arbitrate()
            }
            val session = trackedSessions[activeSessionKey] ?: return@post
            val live = session.controller.metadata
            if (live != null && !isSameTrack(extractTrack(live, session.controller.packageName), activeTrack)) {
                session.metadata = live
                if (updateMetadata(session)) return@post
            }
            if (activeTrack == null || observePosition(session)) return@post
            scrobbleEngine.onTimeTick()
            if (session.isPlaying()) activeTrack?.let { sendNowPlaying(it) }
        }
    }

    private suspend fun finishActive(reason: FinalizeReason) {
        scrobbleEngine.finalizeCurrentSession(reason)
        clearActive()
    }

    private fun resetTimers() {
        pausedSince = null
        pauseTimeoutJob?.cancel()
        nowPlayingJob?.cancel()
        lastNowPlayingAttempt = null
        lastNowPlayingSucceeded = false
    }

    private fun clearActive() {
        resetTimers()
        activeSessionKey = null
        activeTrack = null
        lastPosition = 0L
        _activeTrackFlow.value = null
        _isPlayingFlow.value = false
        _positionFlow.value = 0L
        _artworkBitmapFlow.value = null
        _artworkUriFlow.value = null
    }

    private fun isSameTrack(first: Track?, second: Track?): Boolean =
        first != null && second != null &&
            first.artist.equals(second.artist, ignoreCase = true) && first.title.equals(second.title, ignoreCase = true)

    private fun sendNowPlaying(track: Track, force: Boolean = false) {
        if (!settings.sendNowPlaying || nowPlayingJob?.isActive == true) return
        val now = clock.elapsedRealtime()
        val interval = when {
            force -> 10_000L
            lastNowPlayingSucceeded -> 150_000L
            else -> 30_000L
        }
        if (lastNowPlayingAttempt?.let { now - it < interval } == true) return
        lastNowPlayingAttempt = now
        lastNowPlayingSucceeded = false
        nowPlayingJob = scope.launch {
            val key = authRepository.getSessionKey()
            if (key.isNullOrBlank() || !networkDetector.isOnline()) return@launch
            val result = nowPlayingMutex.withLock {
                lastFmClient.updateNowPlaying(track.artist, track.title, track.album, track.albumArtist,
                    track.durationMs?.let { (it / 1_000).toInt() }, key)
            }
            post {
                if (lastNowPlayingAttempt == now && isSameTrack(track, activeTrack)) {
                    lastNowPlayingSucceeded = result is com.sscrobbler.app.lastfm.LastFmResult.Success
                }
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
        settingsJob.cancel()
        post {
            trackedSessions.values.forEach { it.controller.unregisterCallback(it) }
            trackedSessions.clear()
            clearActive()
        }
        events.close()
    }
}
