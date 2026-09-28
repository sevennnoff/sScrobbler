package com.sscrobbler.app.model

import com.sscrobbler.app.util.Clock

data class PlaybackSession(
    val track: Track,
    val startedAtUnix: Long,
    var listenedMs: Long = 0L,
    var lastPlayStartedElapsedMs: Long? = null,
    var eligible: Boolean = false
) {
    val isPlaying: Boolean
        get() = lastPlayStartedElapsedMs != null

    fun onPlay(clock: Clock) {
        if (lastPlayStartedElapsedMs == null) {
            lastPlayStartedElapsedMs = clock.elapsedRealtime()
        }
    }

    fun onPauseOrStop(clock: Clock) {
        lastPlayStartedElapsedMs?.let { start ->
            val now = clock.elapsedRealtime()
            if (now > start) {
                listenedMs += (now - start)
            }
            lastPlayStartedElapsedMs = null
        }
    }

    fun totalListenedMs(clock: Clock): Long {
        val ongoing = lastPlayStartedElapsedMs?.let { start ->
            val now = clock.elapsedRealtime()
            if (now > start) now - start else 0L
        } ?: 0L
        return listenedMs + ongoing
    }
}
