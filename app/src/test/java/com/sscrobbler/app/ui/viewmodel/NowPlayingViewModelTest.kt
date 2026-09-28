package com.sscrobbler.app.ui.viewmodel

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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var playbackTracker: PlaybackTracker
    private lateinit var scrobbleEngine: ScrobbleEngine
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var historyDao: HistoryDao
    private lateinit var clock: Clock

    private val activeTrackFlow = MutableStateFlow<Track?>(null)
    private val statusFlow = MutableStateFlow<ScrobbleStatus>(ScrobbleStatus.Listening)
    private val isPlayingFlow = MutableStateFlow(false)
    private val positionFlow = MutableStateFlow(0L)
    private val currentSessionFlow = MutableStateFlow<PlaybackSession?>(null)
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val artworkFlow = MutableStateFlow<android.graphics.Bitmap?>(null)
    private val recentFlow = MutableStateFlow<List<HistoryItemEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        playbackTracker = mockk(relaxed = true)
        scrobbleEngine = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        historyDao = mockk(relaxed = true)
        clock = object : Clock {
            override fun currentTimeMillis(): Long = 1700000000000L
            override fun elapsedRealtime(): Long = 50000L
        }

        coEvery { playbackTracker.activeTrackFlow } returns activeTrackFlow
        coEvery { scrobbleEngine.statusFlow } returns statusFlow
        coEvery { playbackTracker.isPlayingFlow } returns isPlayingFlow
        coEvery { playbackTracker.positionFlow } returns positionFlow
        coEvery { scrobbleEngine.currentSessionFlow } returns currentSessionFlow
        coEvery { settingsRepository.settingsFlow } returns settingsFlow
        coEvery { playbackTracker.artworkBitmapFlow } returns artworkFlow
        coEvery { historyDao.getRecentFlow(1) } returns recentFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialIdleState() = runTest(testDispatcher) {
        val viewModel = NowPlayingViewModel(
            playbackTracker = playbackTracker,
            scrobbleEngine = scrobbleEngine,
            settingsRepository = settingsRepository,
            historyDao = historyDao,
            clock = clock
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue("Should be in idle state when no track is playing", state.isIdle)
        assertEquals(0f, state.trackProgress, 0.01f)
        assertEquals(0f, state.thresholdProgress, 0.01f)
    }

    @Test
    fun testActiveTrackStateAndThresholdProgress() = runTest(testDispatcher) {
        val viewModel = NowPlayingViewModel(
            playbackTracker = playbackTracker,
            scrobbleEngine = scrobbleEngine,
            settingsRepository = settingsRepository,
            historyDao = historyDao,
            clock = clock
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        runCurrent()

        val track = Track(
            artist = "Lana Del Rey",
            title = "National Anthem",
            album = "Born to Die",
            durationMs = 240_000L,
            sourcePackage = "com.spotify.music"
        )
        val session = PlaybackSession(
            track = track,
            startedAtUnix = 1700000000L,
            listenedMs = 60_000L,
            eligible = false
        )

        activeTrackFlow.value = track
        currentSessionFlow.value = session
        statusFlow.value = ScrobbleStatus.Listening
        isPlayingFlow.value = true
        positionFlow.value = 60_000L
        runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isIdle)
        assertEquals("National Anthem", state.track?.title)
        assertEquals("Lana Del Rey", state.track?.artist)
        assertEquals(ScrobbleStatus.Listening, state.status)
        assertTrue(state.isPlaying)
        assertEquals("1:00", state.listenedFormatted)
        assertEquals("4:00", state.durationFormatted)
        // 50% of 240s is 120s = 2:00
        assertEquals("2:00", state.thresholdFormatted)
        // 60s listened / 120s threshold = 0.5f progress
        assertEquals(0.5f, state.thresholdProgress, 0.01f)
        assertEquals(0.25f, state.trackProgress, 0.01f)
    }

    @Test
    fun testEligibleWaitingForEndState() = runTest(testDispatcher) {
        val viewModel = NowPlayingViewModel(
            playbackTracker = playbackTracker,
            scrobbleEngine = scrobbleEngine,
            settingsRepository = settingsRepository,
            historyDao = historyDao,
            clock = clock
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        runCurrent()

        val track = Track(
            artist = "Radiohead",
            title = "Karma Police",
            durationMs = 200_000L
        )
        val session = PlaybackSession(
            track = track,
            startedAtUnix = 1700000000L,
            listenedMs = 100_000L,
            eligible = true
        )

        activeTrackFlow.value = track
        currentSessionFlow.value = session
        statusFlow.value = ScrobbleStatus.WaitingForEnd
        isPlayingFlow.value = true
        positionFlow.value = 100_000L
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(ScrobbleStatus.WaitingForEnd, state.status)
        assertEquals(1.0f, state.thresholdProgress, 0.01f)
    }
}
