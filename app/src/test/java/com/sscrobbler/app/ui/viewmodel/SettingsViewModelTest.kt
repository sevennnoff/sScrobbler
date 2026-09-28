package com.sscrobbler.app.ui.viewmodel

import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.media.PlaybackTracker
import com.sscrobbler.app.settings.AppSettings
import com.sscrobbler.app.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var authRepository: LastFmAuthRepository
    private lateinit var playbackTracker: PlaybackTracker

    private val usernameFlow = MutableStateFlow<String?>("test_user")
    private val isLoggedInFlow = MutableStateFlow(true)
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val discoveredPackagesFlow = MutableStateFlow<Set<String>>(emptySet())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        settingsRepository = mockk(relaxed = true)
        authRepository = mockk(relaxed = true)
        playbackTracker = mockk(relaxed = true)

        coEvery { authRepository.usernameFlow } returns usernameFlow
        coEvery { authRepository.isLoggedInFlow } returns isLoggedInFlow
        coEvery { settingsRepository.settingsFlow } returns settingsFlow
        coEvery { playbackTracker.discoveredPackagesFlow } returns discoveredPackagesFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSettingsUiStateReflectsRepository() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(
            settingsRepository = settingsRepository,
            authRepository = authRepository,
            playbackTracker = playbackTracker
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.isLoggedIn)
        assertEquals("test_user", state.username)
        assertEquals(50, state.minListenedPercent)
        assertEquals(240_000L, state.maxRequiredTimeMs)
        assertEquals(30_000L, state.minTrackDurationMs)
        assertEquals(1_800_000L, state.pauseTimeoutMs)
        assertTrue(state.sendNowPlaying)
        assertTrue("Should include default known music apps", state.appSources.isNotEmpty())
    }

    @Test
    fun testPreferencesUpdateDelegations() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(
            settingsRepository = settingsRepository,
            authRepository = authRepository,
            playbackTracker = playbackTracker
        )
        runCurrent()

        viewModel.updateMinListenedPercent(60)
        runCurrent()
        coVerify(exactly = 1) { settingsRepository.updateMinListenedPercent(60) }

        viewModel.updateMaxRequiredTimeMs(180_000L)
        runCurrent()
        coVerify(exactly = 1) { settingsRepository.updateMaxRequiredTimeMs(180_000L) }

        viewModel.updateMinTrackDurationMs(15_000L)
        runCurrent()
        coVerify(exactly = 1) { settingsRepository.updateMinTrackDurationMs(15_000L) }

        viewModel.updatePauseTimeoutMs(300_000L)
        runCurrent()
        coVerify(exactly = 1) { settingsRepository.updatePauseTimeoutMs(300_000L) }

        viewModel.updateSendNowPlaying(false)
        runCurrent()
        coVerify(exactly = 1) { settingsRepository.updateSendNowPlaying(false) }

        viewModel.setPackageAllowed("com.spotify.music", false)
        runCurrent()
        coVerify(exactly = 1) { settingsRepository.setPackageAllowed("com.spotify.music", false) }
    }

    @Test
    fun testDisconnectLastFmCallsAuthRepository() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(
            settingsRepository = settingsRepository,
            authRepository = authRepository,
            playbackTracker = playbackTracker
        )
        runCurrent()

        viewModel.disconnectLastFm()
        runCurrent()

        coVerify(exactly = 1) { authRepository.clearSession() }
    }
}
