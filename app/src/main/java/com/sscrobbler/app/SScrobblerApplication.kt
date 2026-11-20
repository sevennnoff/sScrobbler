package com.sscrobbler.app

import android.app.Application
import com.sscrobbler.app.scrobble.worker.ScrobbleWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.sscrobbler.app.database.AppDatabase
import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.lastfm.LastFmClient
import com.sscrobbler.app.lastfm.authDataStore
import com.sscrobbler.app.scrobble.ScrobbleEngine
import com.sscrobbler.app.settings.SettingsRepository
import com.sscrobbler.app.settings.settingsDataStore
import com.sscrobbler.app.util.SystemNetworkDetector

class SScrobblerApplication : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val authRepository by lazy { LastFmAuthRepository(authDataStore) }
    val settingsRepository by lazy { SettingsRepository(settingsDataStore) }
    val lastFmClient by lazy { LastFmClient() }
    val networkDetector by lazy { SystemNetworkDetector(this) }

    val scrobbleEngine by lazy {
        ScrobbleEngine(
            pendingScrobbleDao = database.pendingScrobbleDao(),
            historyDao = database.historyDao(),
            settingsRepository = settingsRepository,
            scheduleUpload = { ScrobbleWorker.enqueueNow(this) }
        )
    }

    val playbackTracker by lazy {
        com.sscrobbler.app.media.PlaybackTracker(
            scrobbleEngine = scrobbleEngine,
            settingsRepository = settingsRepository,
            authRepository = authRepository,
            lastFmClient = lastFmClient,
            networkDetector = networkDetector
        )
    }

    override fun onCreate() {
        super.onCreate()
        ScrobbleWorker.schedulePeriodic(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            authRepository.sessionKeyFlow.distinctUntilChanged().collect { key ->
                if (!key.isNullOrBlank()) ScrobbleWorker.enqueueNow(this@SScrobblerApplication)
            }
        }
    }
}
