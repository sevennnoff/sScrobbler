package com.sscrobbler.app.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
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
        instance = this
        val app = applicationContext as? SScrobblerApplication
        if (app != null) {
            playbackTracker = app.playbackTracker
            mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        try {
            com.sscrobbler.app.service.ScrobblerForegroundService.start(this)
            val componentName = ComponentName(this, MediaNotificationListener::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
            refreshActiveSessions()
        } catch (e: SecurityException) {
            // Permission not yet granted or restricted
        } catch (e: Exception) {
            // Fallback for unexpected system errors
        }
    }

    fun refreshActiveSessions() {
        try {
            val componentName = ComponentName(this, MediaNotificationListener::class.java)
            val activeControllers = mediaSessionManager?.getActiveSessions(componentName)
            if (activeControllers != null) {
                val adapters = activeControllers.map { SystemMediaControllerAdapter(it) }
                val app = applicationContext as? SScrobblerApplication
                (playbackTracker ?: app?.playbackTracker)?.onActiveSessionsChanged(adapters)
            }
        } catch (e: Exception) {
            // Ignored
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
        if (instance == this) {
            instance = null
        }
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: Exception) {
            // Ignored
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val extras = sbn.notification?.extras ?: return
        val hasMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                sbn.notification.category == Notification.CATEGORY_TRANSPORT ||
                sbn.notification.category == Notification.CATEGORY_SERVICE
        if (hasMedia) {
            refreshActiveSessions()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        refreshActiveSessions()
    }

    companion object {
        @Volatile
        var instance: MediaNotificationListener? = null

        fun ensureRebound(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val componentName = ComponentName(context, MediaNotificationListener::class.java)
                    requestRebind(componentName)
                } catch (e: Exception) {
                    // Ignored
                }
            }
            instance?.refreshActiveSessions()
        }
    }
}
