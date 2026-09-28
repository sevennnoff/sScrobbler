package com.sscrobbler.app.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.lastfm.LastFmClient
import com.sscrobbler.app.media.PlaybackTracker
import com.sscrobbler.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppSourceItem(
    val packageName: String,
    val displayName: String,
    val isEnabled: Boolean,
    val isInstalled: Boolean = true,
    val iconBitmap: Bitmap? = null
)

data class SettingsUiState(
    val isLoggedIn: Boolean = false,
    val username: String? = null,
    val avatarUrl: String? = null,
    val minListenedPercent: Int = 50,
    val maxRequiredTimeMs: Long = 240_000L,
    val minTrackDurationMs: Long = 30_000L,
    val pauseTimeoutMs: Long = 1_800_000L,
    val sendNowPlaying: Boolean = true,
    val defaultNewAppsAllowed: Boolean = true,
    val appSources: List<AppSourceItem> = emptyList(),
    val isNotificationAccessGranted: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val authRepository: LastFmAuthRepository,
    private val playbackTracker: PlaybackTracker? = null,
    private val lastFmClient: LastFmClient = LastFmClient()
) : ViewModel() {

    private val permissionStateFlow = MutableStateFlow(Pair(false, false))
    private val scannedAppsFlow = MutableStateFlow<Map<String, AppSourceItem>>(emptyMap())
    private var hasScanned = false

    private val knownMusicPackages = setOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.apple.android.music",
        "deezer.android.app",
        "com.aspiro.tidal",
        "com.qobuz.music",
        "com.soundcloud.android",
        "com.maxmpz.audioplayer",
        "app.symfonium.music.player",
        "in.krosbits.musicolet",
        "org.videolan.vlc",
        "ru.yandex.music",
        "com.vkontakte.android",
        "org.lineageos.eleven",
        "com.audiomack",
        "com.bandcamp.android"
    )

    private val systemBlacklist = setOf(
        "com.android.bluetooth",
        "com.google.android.bluetooth",
        "com.android.soundpicker",
        "com.android.systemui",
        "com.google.android.apps.messaging",
        "com.android.phone",
        "com.android.server.telecom",
        "android",
        "com.google.android.googlequicksearchbox",
        "com.google.android.dialer",
        "com.google.android.apps.tachyon",
        "com.google.android.apps.nexuslauncher",
        "com.google.android.apps.wallpaper",
        "com.google.android.tts"
    )

    init {
        loadAvatarIfMissing()
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        authRepository.usernameFlow,
        authRepository.isLoggedInFlow,
        authRepository.avatarUrlFlow,
        settingsRepository.settingsFlow,
        playbackTracker?.discoveredPackagesFlow ?: MutableStateFlow<Set<String>>(emptySet()),
        permissionStateFlow,
        scannedAppsFlow
    ) { args: Array<Any?> ->
        val username = args[0] as? String
        val isLoggedIn = (args[1] as? Boolean) ?: false
        val avatarUrl = args[2] as? String
        val settings = (args[3] as? com.sscrobbler.app.settings.AppSettings) ?: com.sscrobbler.app.settings.AppSettings()
        val discoveredPackages = (args[4] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
        val perms = (args[5] as? Pair<*, *>)
        val hasNotif = (perms?.first as? Boolean) ?: false
        val hasBattery = (perms?.second as? Boolean) ?: false
        val scannedMap = (args[6] as? Map<*, *>)?.filterKeys { it is String }?.mapKeys { it.key as String }?.mapValues { it.value as AppSourceItem } ?: emptyMap()

        // Only show packages that are in scannedMap or have actively produced MediaSessions
        val combinedKeys = (scannedMap.keys + discoveredPackages)
            .filter { pkg ->
                pkg !in systemBlacklist &&
                !pkg.startsWith("com.android.internal") &&
                !pkg.startsWith("com.android.providers") &&
                !(pkg.startsWith("com.android.") && pkg !in knownMusicPackages) &&
                !(pkg.startsWith("com.google.android.apps.") && pkg != "com.google.android.apps.youtube.music")
            }.toSet()

        val appSources = combinedKeys.map { pkg ->
            val scanned = scannedMap[pkg]
            val displayName = scanned?.displayName
                ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

            val isAllowed = settings.packageFilter[pkg] ?: settings.defaultNewAppsAllowed

            AppSourceItem(
                packageName = pkg,
                displayName = displayName,
                isEnabled = isAllowed,
                isInstalled = scanned?.isInstalled ?: true,
                iconBitmap = scanned?.iconBitmap
            )
        }.sortedBy { it.displayName.lowercase() }

        SettingsUiState(
            isLoggedIn = isLoggedIn,
            username = username,
            avatarUrl = avatarUrl,
            minListenedPercent = settings.minListenedPercent,
            maxRequiredTimeMs = settings.maxRequiredTimeMs,
            minTrackDurationMs = settings.minTrackDurationMs,
            pauseTimeoutMs = settings.pauseTimeoutMs,
            sendNowPlaying = settings.sendNowPlaying,
            defaultNewAppsAllowed = settings.defaultNewAppsAllowed,
            appSources = appSources,
            isNotificationAccessGranted = hasNotif,
            isBatteryOptimizationIgnored = hasBattery
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun scanInstalledApps(context: Context, force: Boolean = false) {
        if (hasScanned && !force) return
        hasScanned = true

        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val detected = mutableSetOf<String>()

            // 1. Query MediaBrowserService and MediaSession services
            val browserServices = pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
            for (res in browserServices) {
                res.serviceInfo?.packageName?.let { detected.add(it) }
            }

            val sessionServices = pm.queryIntentServices(Intent("androidx.media3.session.MediaSessionService"), 0)
            for (res in sessionServices) {
                res.serviceInfo?.packageName?.let { detected.add(it) }
            }

            // 2. Query Media Button receivers
            val mediaReceivers = pm.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON), 0)
            for (res in mediaReceivers) {
                res.activityInfo?.packageName?.let { detected.add(it) }
            }

            // 3. Query all installed packages for AUDIO category or known players
            val allApps = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList()
            }

            for (app in allApps) {
                val pkg = app.packageName
                if (pkg in systemBlacklist || pkg == context.packageName) continue
                if (pkg.startsWith("com.android.") && pkg !in knownMusicPackages) continue
                if (pkg.startsWith("com.google.android.apps.") && pkg != "com.google.android.apps.youtube.music") continue

                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isAudioCategory = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                        app.category == ApplicationInfo.CATEGORY_AUDIO

                val isKnown = pkg in knownMusicPackages

                if (!isSystem || isKnown || isAudioCategory) {
                    if (isAudioCategory || isKnown || detected.contains(pkg)) {
                        detected.add(pkg)
                    }
                }
            }

            val map = mutableMapOf<String, AppSourceItem>()
            for (pkg in detected) {
                if (pkg in systemBlacklist || pkg == context.packageName) continue
                if (pkg.startsWith("com.android.") && pkg !in knownMusicPackages) continue
                if (pkg.startsWith("com.google.android.apps.") && pkg != "com.google.android.apps.youtube.music") continue

                val appInfo = try {
                    pm.getApplicationInfo(pkg, 0)
                } catch (e: Exception) {
                    null
                } ?: continue

                val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
                val icon = runCatching {
                    val drawable = pm.getApplicationIcon(appInfo)
                    if (drawable is BitmapDrawable) {
                        drawable.bitmap
                    } else {
                        val w = drawable.intrinsicWidth.coerceAtLeast(72).coerceAtMost(192)
                        val h = drawable.intrinsicHeight.coerceAtLeast(72).coerceAtMost(192)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bitmap
                    }
                }.getOrNull()

                map[pkg] = AppSourceItem(
                    packageName = pkg,
                    displayName = label,
                    isEnabled = true,
                    isInstalled = true,
                    iconBitmap = icon
                )
            }

            scannedAppsFlow.value = map
        }
    }

    fun loadAvatarIfMissing() {
        viewModelScope.launch {
            val user = authRepository.getUsername() ?: return@launch
            val currentAvatar = authRepository.avatarUrlFlow.first()
            if (currentAvatar.isNullOrBlank()) {
                val url = lastFmClient.getUserAvatarUrl(user)
                if (!url.isNullOrBlank()) {
                    authRepository.saveAvatarUrl(url)
                }
            }
        }
    }

    fun checkPermissions(context: Context) {
        val hasNotificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isBatteryIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false

        permissionStateFlow.value = Pair(hasNotificationAccess, isBatteryIgnored)
        scanInstalledApps(context, force = false)
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

    fun enableAllApps(packages: List<String>) {
        viewModelScope.launch {
            settingsRepository.setAllPackagesAllowed(packages, true)
        }
    }

    fun disableAllApps(packages: List<String>) {
        viewModelScope.launch {
            settingsRepository.setAllPackagesAllowed(packages, false)
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
        private val playbackTracker: PlaybackTracker? = null,
        private val lastFmClient: LastFmClient = LastFmClient()
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                settingsRepository = settingsRepository,
                authRepository = authRepository,
                playbackTracker = playbackTracker,
                lastFmClient = lastFmClient
            ) as T
        }
    }
}
