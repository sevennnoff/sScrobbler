package com.sscrobbler.app.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.media.PlaybackTracker
import com.sscrobbler.app.settings.AppSettings
import com.sscrobbler.app.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppSourceItem(
    val packageName: String,
    val displayName: String,
    val isEnabled: Boolean
)

data class SettingsUiState(
    val isLoggedIn: Boolean = false,
    val username: String? = null,
    val minListenedPercent: Int = 50,
    val maxRequiredTimeMs: Long = 240_000L,
    val minTrackDurationMs: Long = 30_000L,
    val pauseTimeoutMs: Long = 1_800_000L,
    val sendNowPlaying: Boolean = true,
    val appSources: List<AppSourceItem> = emptyList(),
    val isNotificationAccessGranted: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val authRepository: LastFmAuthRepository,
    private val playbackTracker: PlaybackTracker? = null
) : ViewModel() {

    private val permissionStateFlow = MutableStateFlow(Pair(false, false))

    // Well-known players to display even before any session has fired
    private val defaultKnownPackages = listOf(
        "com.spotify.music" to "Spotify",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.apple.android.music" to "Apple Music",
        "deezer.android.app" to "Deezer",
        "com.aspiro.tidal" to "TIDAL",
        "com.qobuz.music" to "Qobuz",
        "com.soundcloud.android" to "SoundCloud",
        "com.maxmpz.audioplayer" to "Poweramp",
        "app.symfonium.music.player" to "Symfonium",
        "in.krosbits.musicolet" to "Musicolet",
        "org.videolan.vlc" to "VLC",
        "ru.yandex.music" to "Yandex Music",
        "com.vkontakte.android" to "VK Music"
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        authRepository.usernameFlow,
        authRepository.isLoggedInFlow,
        settingsRepository.settingsFlow,
        playbackTracker?.discoveredPackagesFlow ?: MutableStateFlow(emptySet()),
        permissionStateFlow
    ) { username, isLoggedIn, settings, discoveredPackages, perms ->
        val allPackages = (defaultKnownPackages.map { it.first } + discoveredPackages).toSet()

        val appSources = allPackages.map { pkg ->
            val defaultName = defaultKnownPackages.firstOrNull { it.first == pkg }?.second
                ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            val isAllowed = settings.packageFilter[pkg] != false
            AppSourceItem(
                packageName = pkg,
                displayName = defaultName,
                isEnabled = isAllowed
            )
        }.sortedBy { it.displayName }

        SettingsUiState(
            isLoggedIn = isLoggedIn,
            username = username,
            minListenedPercent = settings.minListenedPercent,
            maxRequiredTimeMs = settings.maxRequiredTimeMs,
            minTrackDurationMs = settings.minTrackDurationMs,
            pauseTimeoutMs = settings.pauseTimeoutMs,
            sendNowPlaying = settings.sendNowPlaying,
            appSources = appSources,
            isNotificationAccessGranted = perms.first,
            isBatteryOptimizationIgnored = perms.second
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun checkPermissions(context: Context) {
        val hasNotificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isBatteryIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false

        permissionStateFlow.value = Pair(hasNotificationAccess, isBatteryIgnored)
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun updateMinListenedPercent(value: Int) {
        viewModelScope.launch {
            settingsRepository.updateMinListenedPercent(value)
        }
    }

    fun updateMaxRequiredTimeMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.updateMaxRequiredTimeMs(value)
        }
    }

    fun updateMinTrackDurationMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.updateMinTrackDurationMs(value)
        }
    }

    fun updatePauseTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.updatePauseTimeoutMs(value)
        }
    }

    fun updateSendNowPlaying(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSendNowPlaying(enabled)
        }
    }

    fun setPackageAllowed(packageName: String, allowed: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPackageAllowed(packageName, allowed)
        }
    }

    fun disconnectLastFm() {
        viewModelScope.launch {
            authRepository.clearSession()
        }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val authRepository: LastFmAuthRepository,
        private val playbackTracker: PlaybackTracker? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                settingsRepository = settingsRepository,
                authRepository = authRepository,
                playbackTracker = playbackTracker
            ) as T
        }
    }
}
