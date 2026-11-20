package com.sscrobbler.app

import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sscrobbler.app.media.MediaNotificationListener
import com.sscrobbler.app.model.ScrobbleStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerJourneyTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as SScrobblerApplication

    @Test
    fun eligiblePlaySurvivesPauseAndTrackChangeAndAppearsInLocalHistory() {
        val settings = app.settingsRepository
        val initial = runBlocking { settings.getSettings() }
        val onboarded = runBlocking { settings.isOnboardingCompleted() }
        assumeTrue("Run this offline scenario with a signed-out test account", runBlocking { app.authRepository.getSessionKey().isNullOrBlank() })
        val component = ComponentName(app, MediaNotificationListener::class.java).flattenToString()
        val hadAccess = app.packageName in NotificationManagerCompat.getEnabledListenerPackages(app)
        val session = MediaSession(app, "player-journey")
        val since = System.currentTimeMillis() / 1000
        try {
            if (!hadAccess) shell("cmd notification allow_listener $component")
            runBlocking {
                settings.setOnboardingCompleted(true)
                settings.updateMinTrackDurationMs(30_000)
                settings.updateMinListenedPercent(10)
                settings.updateMaxRequiredTimeMs(240_000)
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                await { MediaNotificationListener.instance != null }
                await { ui.onAllNodes(androidx.compose.ui.test.hasText("Waiting for music")).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithText("Waiting for music").assertIsDisplayed()
                screenshot("idle")
                session.setMetadata(metadata("Night Bus"))
                session.setPlaybackState(state(PlaybackState.STATE_PLAYING, 0))
                session.isActive = true

                await { app.playbackTracker.activeTrackFlow.value?.title == "Night Bus" }
                ui.onNodeWithText("Night Bus").performScrollTo().assertIsDisplayed()
                screenshot("playing")
                await { app.scrobbleEngine.statusFlow.value == ScrobbleStatus.WaitingForEnd }
                session.setPlaybackState(state(PlaybackState.STATE_PAUSED, 6_000))
                await { app.scrobbleEngine.statusFlow.value == ScrobbleStatus.Eligible }
                ui.onNodeWithText("Paused · ready").performScrollTo().assertIsDisplayed()
                screenshot("paused")
                assertEquals(0, runBlocking { testRows(since).size })

                session.setMetadata(metadata("Morning Train"))
                await { app.playbackTracker.activeTrackFlow.value?.title == "Morning Train" }
                val pending = runBlocking {
                    withTimeout(10_000) {
                        app.database.pendingScrobbleDao().getAllFlow().first { rows ->
                            rows.any { row -> row.sourcePackage == app.packageName && row.timestamp >= since && row.title == "Night Bus" }
                        }
                    }
                }
                assertEquals(1, pending.count { row -> row.title == "Night Bus" && row.timestamp >= since && row.sourcePackage == app.packageName })
                assertEquals(ScrobbleStatus.Pending, runBlocking { testRows(since).single().status })
                ui.onNodeWithContentDescription("History").performClick()
                ui.onNodeWithText("On device").performClick()
                await { ui.onAllNodes(androidx.compose.ui.test.hasText("Queued")).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithText("Night Bus").assertIsDisplayed()
                screenshot("history")
                ui.onNodeWithContentDescription("Settings").performClick()
                ui.onNodeWithText("Scrobbling Preferences").performScrollTo().assertIsDisplayed()
                screenshot("settings")
            }
        } finally {
            session.release()
            runBlocking {
                withTimeout(10_000) { app.playbackTracker.activeTrackFlow.first { it?.sourcePackage != app.packageName } }
                for (row in testRows(since)) {
                    app.database.pendingScrobbleDao().deleteById(row.id)
                    app.database.historyDao().deleteById(row.id)
                }
                settings.updateMinListenedPercent(initial.minListenedPercent)
                settings.updateMinTrackDurationMs(initial.minTrackDurationMs)
                settings.updateMaxRequiredTimeMs(initial.maxRequiredTimeMs)
                settings.setOnboardingCompleted(onboarded)
            }
            if (!hadAccess) shell("cmd notification disallow_listener $component")
        }
    }

    private fun await(condition: () -> Boolean) = ui.waitUntil(timeoutMillis = 25_000, condition = condition)

    private suspend fun testRows(since: Long) = app.database.historyDao().getRecent(50).filter {
        it.sourcePackage == app.packageName && it.timestamp >= since && it.title in setOf("Night Bus", "Morning Train")
    }

    private fun state(state: Int, position: Long) = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
        .setState(state, position, if (state == PlaybackState.STATE_PLAYING) 1f else 0f).build()

    private fun metadata(title: String): MediaMetadata {
        val art = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
        Canvas(art).apply {
            drawColor(0xFF2B214B.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in 0..6) {
                paint.color = if (i % 2 == 0) 0xFFD5BBFF.toInt() else 0xFF553775.toInt()
                drawCircle(120f, 240f, 280f - 32f * i, paint)
            }
            paint.color = 0xFFF6AF93.toInt()
            drawCircle(292f, 92f, 48f, paint)
        }
        return MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_ARTIST, "Signal Window")
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "After Hours")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 45_000)
            .putBitmap(MediaMetadata.METADATA_KEY_ART, art).build()
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val dark = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val directory = File(app.filesDir, "screenshots/" + if (dark) "dark" else "light").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
