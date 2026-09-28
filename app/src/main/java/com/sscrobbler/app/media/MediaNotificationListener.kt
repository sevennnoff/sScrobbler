package com.sscrobbler.app.media

import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.sscrobbler.app.SScrobblerApplication

class MediaNotificationListener : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var playbackTracker: PlaybackTracker? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        val app = applicationContext as? SScrobblerApplication ?: return@OnActiveSessionsChangedListener
        val tracker = app.playbackTracker
        if (controllers != null) {
            val adapters = controllers.map { SystemMediaControllerAdapter(it) }
            tracker.onActiveSessionsChanged(adapters)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as? SScrobblerApplication
        if (app != null) {
            playbackTracker = app.playbackTracker
            mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val componentName = ComponentName(this, MediaNotificationListener::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)

            val activeControllers = mediaSessionManager?.getActiveSessions(componentName)
            if (activeControllers != null) {
                val adapters = activeControllers.map { SystemMediaControllerAdapter(it) }
                playbackTracker?.onActiveSessionsChanged(adapters)
            }
        } catch (e: SecurityException) {
            // Permission not yet granted or restricted
        } catch (e: Exception) {
            // Fallback for unexpected system errors
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: Exception) {
            // Ignored
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: Exception) {
            // Ignored
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
