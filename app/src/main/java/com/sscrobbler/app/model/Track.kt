package com.sscrobbler.app.model

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val artist: String,
    val title: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long? = null,
    val sourcePackage: String = ""
)
