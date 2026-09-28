package com.sscrobbler.app.ui.viewmodel

import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.lastfm.LastFmClient
import com.sscrobbler.app.lastfm.LastFmResult
import com.sscrobbler.app.lastfm.LastFmSession
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
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var authRepository: LastFmAuthRepository
    private lateinit var lastFmClient: LastFmClient

    private val usernameFlow = MutableStateFlow<String?>(null)
    private val isOnboardingCompletedFlow = MutableStateFlow(false)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        settingsRepository = mockk(relaxed = true)
        authRepository = mockk(relaxed = true)
        lastFmClient = mockk(relaxed = true)

        coEvery { authRepository.usernameFlow } returns usernameFlow
        coEvery { authRepository.getUsername() } returns null
        coEvery { settingsRepository.isOnboardingCompletedFlow } returns isOnboardingCompletedFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testStepNavigation() = runTest(testDispatcher) {
        val viewModel = OnboardingViewModel(
            settingsRepository = settingsRepository,
            authRepository = authRepository,
            lastFmClient = lastFmClient
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        runCurrent()

        assertEquals(0, viewModel.uiState.value.currentStep)

        viewModel.nextStep()
        runCurrent()
        assertEquals(1, viewModel.uiState.value.currentStep)

        viewModel.nextStep()
        runCurrent()
        assertEquals(2, viewModel.uiState.value.currentStep)

        // Cannot exceed step 2
        viewModel.nextStep()
        runCurrent()
        assertEquals(2, viewModel.uiState.value.currentStep)

        viewModel.prevStep()
        runCurrent()
        assertEquals(1, viewModel.uiState.value.currentStep)
    }

    @Test
    fun testConfirmBrowserAuthSuccess() = runTest(testDispatcher) {
        val viewModel = OnboardingViewModel(
            settingsRepository = settingsRepository,
            authRepository = authRepository,
            lastFmClient = lastFmClient
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        runCurrent()

        coEvery { lastFmClient.getSession("mock_token") } returns LastFmResult.Success(
            LastFmSession("test_listener", "mock_session_key")
        )

        // Inject token via private property reflection or helper flow
        val tokenField = OnboardingViewModel::class.java.getDeclaredField("currentToken")
        tokenField.isAccessible = true
        tokenField.set(viewModel, "mock_token")

        viewModel.confirmBrowserAuth()
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue("Auth state should be Connected", state.authState is LastFmAuthState.Connected)
        assertEquals("test_listener", (state.authState as LastFmAuthState.Connected).username)
        coVerify(exactly = 1) { authRepository.saveSession("test_listener", "mock_session_key") }
    }

    @Test
    fun testCompleteOnboarding() = runTest(testDispatcher) {
        val viewModel = OnboardingViewModel(
            settingsRepository = settingsRepository,
            authRepository = authRepository,
            lastFmClient = lastFmClient
        )
        runCurrent()

        viewModel.completeOnboarding()
        runCurrent()

        coVerify(exactly = 1) { settingsRepository.setOnboardingCompleted(true) }
    }
}
