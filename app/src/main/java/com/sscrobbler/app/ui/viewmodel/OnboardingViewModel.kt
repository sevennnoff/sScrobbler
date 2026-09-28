package com.sscrobbler.app.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sscrobbler.app.BuildConfig
import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.lastfm.LastFmClient
import com.sscrobbler.app.lastfm.LastFmResult
import com.sscrobbler.app.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LastFmAuthState {
    object Idle : LastFmAuthState
    object LoadingToken : LastFmAuthState
    data class WaitingForBrowser(val token: String) : LastFmAuthState
    object FetchingSession : LastFmAuthState
    data class Connected(val username: String) : LastFmAuthState
    data class Error(val message: String) : LastFmAuthState
}

data class OnboardingUiState(
    val currentStep: Int = 0,
    val isNotificationAccessGranted: Boolean = false,
    val authState: LastFmAuthState = LastFmAuthState.Idle,
    val isCompleted: Boolean = false
)

class OnboardingViewModel(
    private val settingsRepository: SettingsRepository,
    private val authRepository: LastFmAuthRepository,
    private val lastFmClient: LastFmClient
) : ViewModel() {

    private val _currentStep = MutableStateFlow(0)
    private val _notificationGranted = MutableStateFlow(false)
    private val _authState = MutableStateFlow<LastFmAuthState>(LastFmAuthState.Idle)
    private var currentToken: String? = null

    val uiState: StateFlow<OnboardingUiState> = combine(
        _currentStep,
        _notificationGranted,
        _authState,
        authRepository.usernameFlow,
        settingsRepository.isOnboardingCompletedFlow
    ) { step, notif, auth, savedUser, completed ->
        val effectiveAuth = if (!savedUser.isNullOrBlank() && auth !is LastFmAuthState.Connected) {
            LastFmAuthState.Connected(savedUser)
        } else {
            auth
        }
        OnboardingUiState(
            currentStep = step,
            isNotificationAccessGranted = notif,
            authState = effectiveAuth,
            isCompleted = completed
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = OnboardingUiState()
    )

    init {
        viewModelScope.launch {
            val user = authRepository.getUsername()
            if (!user.isNullOrBlank()) {
                _authState.value = LastFmAuthState.Connected(user)
            }
        }
    }

    fun setStep(step: Int) {
        _currentStep.value = step.coerceIn(0, 2)
    }

    fun nextStep() {
        _currentStep.value = (_currentStep.value + 1).coerceAtMost(2)
    }

    fun prevStep() {
        _currentStep.value = (_currentStep.value - 1).coerceAtLeast(0)
    }

    fun checkNotificationAccess(context: Context) {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        _notificationGranted.value = granted
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun startBrowserAuth(context: Context) {
        viewModelScope.launch {
            _authState.value = LastFmAuthState.LoadingToken
            when (val res = lastFmClient.getToken()) {
                is LastFmResult.Success -> {
                    val token = res.data
                    currentToken = token
                    _authState.value = LastFmAuthState.WaitingForBrowser(token)

                    val authUrl = "https://www.last.fm/api/auth/?api_key=${BuildConfig.LASTFM_API_KEY}&token=$token"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        _authState.value = LastFmAuthState.Error("Could not open browser: ${e.message}")
                    }
                }
                is LastFmResult.Error -> {
                    _authState.value = LastFmAuthState.Error(res.message)
                }
            }
        }
    }

    fun confirmBrowserAuth(tokenOverride: String? = null) {
        val token = tokenOverride ?: currentToken
        if (token.isNullOrBlank()) {
            _authState.value = LastFmAuthState.Error("No token requested yet. Please click 'Open Last.fm Login'.")
            return
        }

        viewModelScope.launch {
            _authState.value = LastFmAuthState.FetchingSession
            when (val res = lastFmClient.getSession(token)) {
                is LastFmResult.Success -> {
                    val session = res.data
                    authRepository.saveSession(session.name, session.key)
                    settingsRepository.setOnboardingCompleted(true)
                    _authState.value = LastFmAuthState.Connected(session.name)
                }
                is LastFmResult.Error -> {
                    _authState.value = LastFmAuthState.Error("Authorization not confirmed: ${res.message}")
                }
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted(true)
        }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val authRepository: LastFmAuthRepository,
        private val lastFmClient: LastFmClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return OnboardingViewModel(
                settingsRepository = settingsRepository,
                authRepository = authRepository,
                lastFmClient = lastFmClient
            ) as T
        }
    }
}
