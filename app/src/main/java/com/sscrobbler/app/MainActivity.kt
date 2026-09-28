package com.sscrobbler.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sscrobbler.app.ui.navigation.MainNavigation
import com.sscrobbler.app.ui.theme.SScrobblerTheme
import com.sscrobbler.app.ui.viewmodel.HistoryViewModel
import com.sscrobbler.app.ui.viewmodel.NowPlayingViewModel
import com.sscrobbler.app.ui.viewmodel.OnboardingViewModel
import com.sscrobbler.app.ui.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val app by lazy { application as SScrobblerApplication }

    private val nowPlayingViewModel: NowPlayingViewModel by viewModels {
        NowPlayingViewModel.Factory(
            playbackTracker = app.playbackTracker,
            scrobbleEngine = app.scrobbleEngine,
            settingsRepository = app.settingsRepository,
            historyDao = app.database.historyDao()
        )
    }

    private val historyViewModel: HistoryViewModel by viewModels {
        HistoryViewModel.Factory(
            historyDao = app.database.historyDao()
        )
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(
            settingsRepository = app.settingsRepository,
            authRepository = app.authRepository,
            playbackTracker = app.playbackTracker
        )
    }

    private val onboardingViewModel: OnboardingViewModel by viewModels {
        OnboardingViewModel.Factory(
            settingsRepository = app.settingsRepository,
            authRepository = app.authRepository,
            lastFmClient = app.lastFmClient
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleAuthIntent(intent)

        setContent {
            SScrobblerTheme {
                val isOnboarded by app.settingsRepository.isOnboardingCompletedFlow.collectAsState(initial = false)

                Surface(modifier = Modifier.fillMaxSize()) {
                    MainNavigation(
                        nowPlayingViewModel = nowPlayingViewModel,
                        historyViewModel = historyViewModel,
                        settingsViewModel = settingsViewModel,
                        onboardingViewModel = onboardingViewModel,
                        isOnboardingCompleted = isOnboarded
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthIntent(intent)
    }

    private fun handleAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "sscrobbler" && data.host == "auth") {
            onboardingViewModel.confirmBrowserAuth()
        }
    }
}
