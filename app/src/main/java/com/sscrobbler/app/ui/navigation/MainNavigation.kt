package com.sscrobbler.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sscrobbler.app.ui.screens.AppsScreen
import com.sscrobbler.app.ui.screens.HistoryScreen
import com.sscrobbler.app.ui.screens.NowPlayingScreen
import com.sscrobbler.app.ui.screens.OnboardingScreen
import com.sscrobbler.app.ui.screens.SettingsScreen
import com.sscrobbler.app.ui.viewmodel.HistoryViewModel
import com.sscrobbler.app.ui.viewmodel.NowPlayingViewModel
import com.sscrobbler.app.ui.viewmodel.OnboardingViewModel
import com.sscrobbler.app.ui.viewmodel.SettingsViewModel

enum class AppDestination {
    NowPlaying,
    History,
    Settings,
    Apps,
    Onboarding
}

@Composable
fun MainNavigation(
    nowPlayingViewModel: NowPlayingViewModel,
    historyViewModel: HistoryViewModel,
    settingsViewModel: SettingsViewModel,
    onboardingViewModel: OnboardingViewModel,
    isOnboardingCompleted: Boolean,
    modifier: Modifier = Modifier
) {
    var currentDestination by rememberSaveable {
        mutableStateOf(if (isOnboardingCompleted) AppDestination.NowPlaying else AppDestination.Onboarding)
    }

    // Predictive back handling
    BackHandler(enabled = currentDestination != AppDestination.NowPlaying && currentDestination != AppDestination.Onboarding) {
        when (currentDestination) {
            AppDestination.Apps -> currentDestination = AppDestination.Settings
            AppDestination.History, AppDestination.Settings -> currentDestination = AppDestination.NowPlaying
            else -> {}
        }
    }

    val showBottomBar = currentDestination in listOf(
        AppDestination.NowPlaying,
        AppDestination.History,
        AppDestination.Settings
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentDestination == AppDestination.NowPlaying,
                        onClick = { currentDestination = AppDestination.NowPlaying },
                        icon = { Icon(Icons.Default.MusicNote, contentDescription = "Now Playing") },
                        label = { Text("Now Playing") }
                    )
                    NavigationBarItem(
                        selected = currentDestination == AppDestination.History,
                        onClick = { currentDestination = AppDestination.History },
                        icon = { Icon(Icons.Default.History, contentDescription = "History") },
                        label = { Text("History") }
                    )
                    NavigationBarItem(
                        selected = currentDestination == AppDestination.Settings,
                        onClick = { currentDestination = AppDestination.Settings },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") }
                    )
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentDestination,
            transitionSpec = { fadeIn().togetherWith(fadeOut()) },
            label = "screenTransition",
            modifier = Modifier.padding(innerPadding)
        ) { destination ->
            when (destination) {
                AppDestination.NowPlaying -> NowPlayingScreen(
                    viewModel = nowPlayingViewModel
                )
                AppDestination.History -> HistoryScreen(
                    viewModel = historyViewModel
                )
                AppDestination.Settings -> SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateToApps = { currentDestination = AppDestination.Apps },
                    onNavigateToLogin = { currentDestination = AppDestination.Onboarding }
                )
                AppDestination.Apps -> AppsScreen(
                    viewModel = settingsViewModel,
                    onBack = { currentDestination = AppDestination.Settings }
                )
                AppDestination.Onboarding -> OnboardingScreen(
                    viewModel = onboardingViewModel,
                    onFinished = { currentDestination = AppDestination.NowPlaying }
                )
            }
        }
    }
}
