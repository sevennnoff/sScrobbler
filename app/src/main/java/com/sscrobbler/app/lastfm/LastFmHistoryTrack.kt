package com.sscrobbler.app.lastfm

data class LastFmHistoryTrack(
    val id: String,
    val artist: String,
    val title: String,
    val album: String?,
    val timestamp: Long,
    val timeFormatted: String,
    val isNowPlaying: Boolean = false,
    val artworkUrl: String? = null
)
