package com.sscrobbler.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sscrobbler.app.MainActivity
import com.sscrobbler.app.R
import com.sscrobbler.app.SScrobblerApplication
import com.sscrobbler.app.model.ScrobbleStatus
import com.sscrobbler.app.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ScrobblerForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val initialNotification = buildNotification(null, ScrobbleStatus.Listening, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
        observePlayback()
    }

    private fun observePlayback() {
        val app = applicationContext as? SScrobblerApplication ?: return
        scope.launch {
            combine(
                app.playbackTracker.activeTrackFlow,
                app.scrobbleEngine.statusFlow,
                app.playbackTracker.isPlayingFlow
            ) { track, status, isPlaying ->
                Triple(track, status, isPlaying)
            }.collect { (track, status, isPlaying) ->
                val notification = buildNotification(track, status, isPlaying)
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(track: Track?, status: ScrobbleStatus, isPlaying: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = track?.title ?: "sScrobbler is running"
        val statusText = when {
            track == null -> "Waiting for music playback"
            status == ScrobbleStatus.Eligible || status == ScrobbleStatus.WaitingForEnd -> "Ready to scrobble • Will submit on end"
            !isPlaying || status == ScrobbleStatus.Paused -> "Paused"
            status == ScrobbleStatus.Scrobbled -> "Scrobbled to Last.fm"
            else -> "Listening"
        }
        val contentText = if (track != null) "${track.artist} • $statusText" else statusText

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "sScrobbler Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live scrobble status in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "sscrobbler_active_channel"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            try {
                val intent = Intent(context, ScrobblerForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Background start restriction fallback
            }
        }
    }
}
