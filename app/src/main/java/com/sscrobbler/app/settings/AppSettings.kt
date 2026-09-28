package com.sscrobbler.app.settings

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val minListenedPercent: Int = 50,
    val maxRequiredTimeMs: Long = 240_000L,
    val minTrackDurationMs: Long = 30_000L,
    val pauseTimeoutMs: Long = 1_800_000L,
    val sendNowPlaying: Boolean = true,
    val defaultNewAppsAllowed: Boolean = true,
    val packageFilter: Map<String, Boolean> = emptyMap()
)
