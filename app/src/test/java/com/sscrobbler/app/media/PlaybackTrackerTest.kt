package com.sscrobbler.app.media

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import com.sscrobbler.app.database.HistoryItemEntity
import com.sscrobbler.app.database.PendingScrobbleEntity
import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.lastfm.LastFmClient
import com.sscrobbler.app.lastfm.LastFmResult
import com.sscrobbler.app.model.ScrobbleStatus
import com.sscrobbler.app.model.Track
import com.sscrobbler.app.scrobble.ScrobbleEngine
import com.sscrobbler.app.settings.AppSettings
import com.sscrobbler.app.settings.SettingsRepository
import com.sscrobbler.app.util.Clock
import com.sscrobbler.app.util.NetworkDetector
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeMediaControllerAdapter(
    override val packageName: String,
    initialPlaybackState: PlaybackState? = null,
    initialMetadata: MediaMetadata? = null
) : MediaControllerAdapter {
    override var playbackState: PlaybackState? = initialPlaybackState
    override var metadata: MediaMetadata? = initialMetadata
    val callbacks = mutableListOf<MediaController.Callback>()

    override fun registerCallback(callback: MediaController.Callback) {
        callbacks.add(callback)
    }

    override fun unregisterCallback(callback: MediaController.Callback) {
        callbacks.remove(callback)
    }

    fun dispatchPlaybackState(state: PlaybackState?) {
        playbackState = state
        callbacks.forEach { it.onPlaybackStateChanged(state) }
    }

    fun dispatchMetadata(meta: MediaMetadata?) {
        metadata = meta
        callbacks.forEach { it.onMetadataChanged(meta) }
    }

    fun dispatchSessionDestroyed() {
        callbacks.forEach { it.onSessionDestroyed() }
    }
}

class TestTrackerClock(
    private var currentTime: Long = 1700000000000L,
    private var elapsedRealtime: Long = 100000L
) : Clock {
    override fun currentTimeMillis(): Long = currentTime
    override fun elapsedRealtime(): Long = elapsedRealtime

    fun advance(ms: Long) {
        currentTime += ms
        elapsedRealtime += ms
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTrackerTest {

    private lateinit var testScope: TestScope
    private lateinit var clock: TestTrackerClock
    private lateinit var scrobbleEngine: ScrobbleEngine
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var authRepository: LastFmAuthRepository
    private lateinit var lastFmClient: LastFmClient
    private lateinit var networkDetector: NetworkDetector
    private lateinit var tracker: PlaybackTracker

    private val settingsStateFlow = MutableStateFlow(AppSettings())

    private fun mockPlaybackState(state: Int, position: Long): PlaybackState {
        val mock = mockk<PlaybackState>(relaxed = true)
        coEvery { mock.state } returns state
        coEvery { mock.position } returns position
        coEvery { mock.lastPositionUpdateTime } returns clock.elapsedRealtime()
        coEvery { mock.playbackSpeed } returns 1.0f
        return mock
    }

    private fun mockMetadata(
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long? = 200_000L
    ): MediaMetadata {
        val mock = mockk<MediaMetadata>(relaxed = true)
        coEvery { mock.getString(MediaMetadata.METADATA_KEY_TITLE) } returns title
        coEvery { mock.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) } returns title
        coEvery { mock.getString(MediaMetadata.METADATA_KEY_ARTIST) } returns artist
        coEvery { mock.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) } returns artist
        coEvery { mock.getString(MediaMetadata.METADATA_KEY_AUTHOR) } returns artist
        coEvery { mock.getString(MediaMetadata.METADATA_KEY_ALBUM) } returns album
        coEvery { mock.getLong(MediaMetadata.METADATA_KEY_DURATION) } returns (durationMs ?: 0L)
        return mock
    }

    @Before
    fun setUp() {
        clock = TestTrackerClock()
        scrobbleEngine = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        authRepository = mockk(relaxed = true)
        lastFmClient = mockk(relaxed = true)
        networkDetector = mockk(relaxed = true)

        coEvery { settingsRepository.getSettings() } answers { settingsStateFlow.value }
        coEvery { settingsRepository.settingsFlow } returns settingsStateFlow
        coEvery { authRepository.getSessionKey() } returns "valid_session_key"
        coEvery { networkDetector.isOnline() } returns true
        coEvery { lastFmClient.updateNowPlaying(any(), any(), any(), any(), any(), any()) } returns LastFmResult.Success(Unit)
    }

    @Test
    fun testTrackRepeatDetection_positionDrop70PercentToUnder5Seconds() = runTest {
        tracker = PlaybackTracker(
            scrobbleEngine = scrobbleEngine,
            settingsRepository = settingsRepository,
            authRepository = authRepository,
            lastFmClient = lastFmClient,
            networkDetector = networkDetector,
            clock = clock,
            scope = this
        )

        val controller = FakeMediaControllerAdapter(
            packageName = "com.spotify.music",
            initialPlaybackState = mockPlaybackState(PlaybackState.STATE_PLAYING, 10_000L),
            initialMetadata = mockMetadata("Song 1", "Artist 1", durationMs = 200_000L)
        )

        tracker.registerController(controller)
        runCurrent()

        assertEquals("com.spotify.music", tracker.activeSessionKey)
        coVerify(exactly = 1) { scrobbleEngine.onTrackStarted(match { it.title == "Song 1" }) }

        // Track advances to 160s (> 70% of 200s is 140s)
        clock.advance(150_000L)
        controller.dispatchPlaybackState(mockPlaybackState(PlaybackState.STATE_PLAYING, 160_000L))
        runCurrent()

        // Loop / repeat happens: position drops to 2_000ms (< 5000ms)
        clock.advance(1000L)
        controller.dispatchPlaybackState(mockPlaybackState(PlaybackState.STATE_PLAYING, 2000L))
        runCurrent()

        // Verify finalizeCurrentSession was called with TrackRepeated and new track started
        coVerify(exactly = 1) { scrobbleEngine.finalizeCurrentSession(com.sscrobbler.app.scrobble.FinalizeReason.TrackRepeated) }
        coVerify(exactly = 2) { scrobbleEngine.onTrackStarted(match { it.title == "Song 1" }) }

        tracker.destroy()
    }

    @Test
    fun testSessionArbitration_onePlayingAndOnePaused() = runTest {
        tracker = PlaybackTracker(
            scrobbleEngine = scrobbleEngine,
            settingsRepository = settingsRepository,
            authRepository = authRepository,
            lastFmClient = lastFmClient,
            networkDetector = networkDetector,
            clock = clock,
            scope = this
        )

        // Session 1: Spotify is PAUSED
        val spotify = FakeMediaControllerAdapter(
            packageName = "com.spotify.music",
            initialPlaybackState = mockPlaybackState(PlaybackState.STATE_PAUSED, 30_000L),
            initialMetadata = mockMetadata("Spotify Song", "Spotify Artist")
        )

        // Session 2: Qobuz is PLAYING
        val qobuz = FakeMediaControllerAdapter(
            packageName = "com.qobuz.music",
            initialPlaybackState = mockPlaybackState(PlaybackState.STATE_PLAYING, 10_000L),
            initialMetadata = mockMetadata("Qobuz Song", "Qobuz Artist")
        )

        tracker.onActiveSessionsChanged(listOf(spotify, qobuz))
        runCurrent()

        // Qobuz should be the active session because it is PLAYING
        assertEquals("com.qobuz.music", tracker.activeSessionKey)
        coVerify(exactly = 1) { scrobbleEngine.onTrackStarted(match { it.title == "Qobuz Song" }) }

        // Now Qobuz pauses, and Spotify starts playing
        clock.advance(5000L)
        qobuz.dispatchPlaybackState(mockPlaybackState(PlaybackState.STATE_PAUSED, 15_000L))
        runCurrent()

        clock.advance(1000L)
        spotify.dispatchPlaybackState(mockPlaybackState(PlaybackState.STATE_PLAYING, 31_000L))
        runCurrent()

        // Active session should switch to Spotify
        assertEquals("com.spotify.music", tracker.activeSessionKey)
        coVerify(exactly = 1) { scrobbleEngine.finalizeCurrentSession(com.sscrobbler.app.scrobble.FinalizeReason.TrackChanged) }
        coVerify(exactly = 1) { scrobbleEngine.onTrackStarted(match { it.title == "Spotify Song" }) }

        tracker.destroy()
    }

    @Test
    fun testPauseTimeout_triggersFinalize() = runTest {
        // Configure pause timeout to 5 minutes (300_000 ms)
        settingsStateFlow.value = AppSettings(pauseTimeoutMs = 300_000L)

        tracker = PlaybackTracker(
            scrobbleEngine = scrobbleEngine,
            settingsRepository = settingsRepository,
            authRepository = authRepository,
            lastFmClient = lastFmClient,
            networkDetector = networkDetector,
            clock = clock,
            scope = this
        )

        val controller = FakeMediaControllerAdapter(
            packageName = "com.spotify.music",
            initialPlaybackState = mockPlaybackState(PlaybackState.STATE_PLAYING, 50_000L),
            initialMetadata = mockMetadata("Song A", "Artist A")
        )

        tracker.registerController(controller)
        runCurrent()

        // Put on PAUSE
        controller.dispatchPlaybackState(mockPlaybackState(PlaybackState.STATE_PAUSED, 60_000L))
        runCurrent()

        // Advance 4 minutes (not yet timed out)
        advanceTimeBy(240_000L)
        runCurrent()
        coVerify(exactly = 0) { scrobbleEngine.finalizeCurrentSession(com.sscrobbler.app.scrobble.FinalizeReason.PauseTimeout) }

        // Advance past 5 minutes (total 310_000L)
        advanceTimeBy(70_000L)
        runCurrent()

        coVerify(exactly = 1) { scrobbleEngine.finalizeCurrentSession(com.sscrobbler.app.scrobble.FinalizeReason.PauseTimeout) }
        assertNull(tracker.activeSessionKey)

        tracker.destroy()
    }

    @Test
    fun testNaturalEndDetection_triggersFinalize() = runTest {
        tracker = PlaybackTracker(
            scrobbleEngine = scrobbleEngine,
            settingsRepository = settingsRepository,
            authRepository = authRepository,
            lastFmClient = lastFmClient,
            networkDetector = networkDetector,
            clock = clock,
            scope = this
        )

        // Track duration 180s (180_000 ms)
        val controller = FakeMediaControllerAdapter(
            packageName = "com.spotify.music",
            initialPlaybackState = mockPlaybackState(PlaybackState.STATE_PLAYING, 10_000L),
            initialMetadata = mockMetadata("Ending Song", "Ending Artist", durationMs = 180_000L)
        )

        tracker.registerController(controller)
        runCurrent()

        // State changes to PAUSED / STOPPED at position 178_000ms (>= 180_000 - 3_000ms)
        controller.dispatchPlaybackState(mockPlaybackState(PlaybackState.STATE_PAUSED, 178_000L))
        runCurrent()

        coVerify(exactly = 1) { scrobbleEngine.finalizeCurrentSession(com.sscrobbler.app.scrobble.FinalizeReason.NaturalEnd) }
        assertNull(tracker.activeSessionKey)

        tracker.destroy()
    }
}
