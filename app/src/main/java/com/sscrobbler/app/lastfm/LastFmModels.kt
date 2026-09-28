package com.sscrobbler.app.lastfm

import kotlinx.serialization.Serializable

@Serializable
data class LastFmTokenResponse(
    val token: String? = null,
    val error: Int? = null,
    val message: String? = null
)

@Serializable
data class LastFmSession(
    val name: String,
    val key: String,
    val subscriber: Int = 0
)

@Serializable
data class LastFmSessionResponse(
    val session: LastFmSession? = null,
    val error: Int? = null,
    val message: String? = null
)

@Serializable
data class LastFmScrobbleAttr(
    val accepted: Int = 0,
    val ignored: Int = 0
)

@Serializable
data class LastFmScrobbleResponse(
    val error: Int? = null,
    val message: String? = null
)

sealed class LastFmResult<out T> {
    data class Success<out T>(val data: T) : LastFmResult<T>()
    data class Error(val code: Int?, val message: String, val throwable: Throwable? = null) : LastFmResult<Nothing>()
}
