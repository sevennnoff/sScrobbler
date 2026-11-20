package com.sscrobbler.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sscrobbler.app.ui.screens.AppsScreen
import com.sscrobbler.app.ui.screens.DebugScreen
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
    Debug,
    Onboarding
}

private data class Tab(val route: AppDestination, val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab(AppDestination.NowPlaying, "Now playing", Icons.Default.MusicNote),
    Tab(AppDestination.History, "History", Icons.Default.History),
    Tab(AppDestination.Settings, "Settings", Icons.Default.Settings)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainNavigation(
    nowPlayingViewModel: NowPlayingViewModel,
    historyViewModel: HistoryViewModel,
    settingsViewModel: SettingsViewModel,
    onboardingViewModel: OnboardingViewModel,
    initialDestination: AppDestination = AppDestination.NowPlaying,
    modifier: Modifier = Modifier
) {
    var currentDestination by rememberSaveable { mutableStateOf(initialDestination) }
    val states = rememberSaveableStateHolder()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    BackHandler(enabled = currentDestination != AppDestination.NowPlaying && currentDestination != AppDestination.Onboarding) {
        currentDestination = when (currentDestination) {
            AppDestination.Apps, AppDestination.Debug -> AppDestination.Settings
            else -> AppDestination.NowPlaying
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (tabs.any { it.route == currentDestination }) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(
                            Modifier.widthIn(max = 450.dp).fillMaxWidth().selectableGroup().padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (tab in tabs) {
                                val selected = tab.route == currentDestination
                                val interaction = remember { MutableInteractionSource() }
                                val pressed by interaction.collectIsPressedAsState()
                                val weight by animateFloatAsState(
                                    if (selected) 1.85f else if (pressed) 1.2f else 1f,
                                    MaterialTheme.motionScheme.fastSpatialSpec(), label = "tab width"
                                )
                                val radius by animateDpAsState(if (pressed) 14.dp else 28.dp,
                                    MaterialTheme.motionScheme.fastSpatialSpec(), label = "tab corners")
                                val ink = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                Row(
                                    Modifier.weight(weight).heightIn(min = 48.dp).clip(RoundedCornerShape(radius))
                                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .selectable(selected = selected, role = Role.Tab, interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current,
                                            onClick = { currentDestination = tab.route })
                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(tab.icon, if (selected) null else tab.label, Modifier.size(22.dp), tint = ink)
                                    AnimatedVisibility(selected) {
                                        Text(tab.label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge,
                                            color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = currentDestination,
            transitionSpec = {
                val sign = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(spatial) { sign * it / 12 } + fadeIn(fade)) togetherWith
                    (slideOutHorizontally(spatial) { -sign * it / 12 } + fadeOut(fade))
            },
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            label = "destination"
        ) { destination ->
            states.SaveableStateProvider(destination.name) {
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
                        onNavigateToLogin = { currentDestination = AppDestination.Onboarding },
                        onOpenDebug = { currentDestination = AppDestination.Debug }
                    )
                    AppDestination.Apps -> AppsScreen(
                        viewModel = settingsViewModel,
                        onBack = { currentDestination = AppDestination.Settings }
                    )
                    AppDestination.Debug -> {
                        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                        DebugScreen(
                            settingsState = settingsState,
                            onBack = { currentDestination = AppDestination.Settings }
                        )
                    }
                    AppDestination.Onboarding -> OnboardingScreen(
                        viewModel = onboardingViewModel,
                        onFinished = { currentDestination = AppDestination.NowPlaying }
                    )
                }
            }
        }
    }
}
