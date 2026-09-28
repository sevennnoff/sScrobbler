package com.sscrobbler.app.lastfm

import com.sscrobbler.app.BuildConfig
import com.sscrobbler.app.database.PendingScrobbleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

open class LastFmClient(
    private val apiKey: String = BuildConfig.LASTFM_API_KEY,
    private val apiSecret: String = BuildConfig.LASTFM_API_SECRET,
    private val okHttpClient: OkHttpClient? = null,
    private val baseUrl: String = "https://ws.audioscrobbler.com/2.0/"
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client: OkHttpClient by lazy {
        okHttpClient ?: OkHttpClient()
    }

    open suspend fun getToken(): LastFmResult<String> = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "method" to "auth.getToken",
            "api_key" to apiKey
        )
        val sig = LastFmSigner.sign(params, apiSecret)
        params["api_sig"] = sig
        params["format"] = "json"

        executePost(params) { responseBody ->
            val parsed = json.decodeFromString<LastFmTokenResponse>(responseBody)
            if (parsed.token != null) {
                LastFmResult.Success(parsed.token)
            } else {
                LastFmResult.Error(parsed.error, parsed.message ?: "Failed to get token")
            }
        }
    }

    open suspend fun getSession(token: String): LastFmResult<LastFmSession> = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "method" to "auth.getSession",
            "api_key" to apiKey,
            "token" to token
        )
        val sig = LastFmSigner.sign(params, apiSecret)
        params["api_sig"] = sig
        params["format"] = "json"

        executePost(params) { responseBody ->
            val parsed = json.decodeFromString<LastFmSessionResponse>(responseBody)
            if (parsed.session != null) {
                LastFmResult.Success(parsed.session)
            } else {
                LastFmResult.Error(parsed.error, parsed.message ?: "Failed to get session")
            }
        }
    }

    open suspend fun updateNowPlaying(
        artist: String,
        track: String,
        album: String? = null,
        albumArtist: String? = null,
        durationSeconds: Int? = null,
        sessionKey: String
    ): LastFmResult<Unit> = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "method" to "track.updateNowPlaying",
            "api_key" to apiKey,
            "sk" to sessionKey,
            "artist" to artist,
            "track" to track
        )
        if (!album.isNullOrBlank()) params["album"] = album
        if (!albumArtist.isNullOrBlank()) params["albumArtist"] = albumArtist
        if (durationSeconds != null && durationSeconds > 0) params["duration"] = durationSeconds.toString()

        val sig = LastFmSigner.sign(params, apiSecret)
        params["api_sig"] = sig
        params["format"] = "json"

        executePost(params) { responseBody ->
            checkError(responseBody)
        }
    }

    open suspend fun scrobble(
        artist: String,
        track: String,
        timestamp: Long,
        album: String? = null,
        albumArtist: String? = null,
        durationSeconds: Int? = null,
        sessionKey: String
    ): LastFmResult<Unit> = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "method" to "track.scrobble",
            "api_key" to apiKey,
            "sk" to sessionKey,
            "artist" to artist,
            "track" to track,
            "timestamp" to timestamp.toString()
        )
        if (!album.isNullOrBlank()) params["album"] = album
        if (!albumArtist.isNullOrBlank()) params["albumArtist"] = albumArtist
        if (durationSeconds != null && durationSeconds > 0) params["duration"] = durationSeconds.toString()

        val sig = LastFmSigner.sign(params, apiSecret)
        params["api_sig"] = sig
        params["format"] = "json"

        executePost(params) { responseBody ->
            checkError(responseBody)
        }
    }

    open suspend fun scrobbleBatch(
        items: List<PendingScrobbleEntity>,
        sessionKey: String
    ): LastFmResult<Unit> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext LastFmResult.Success(Unit)

        val batch = items.take(50)
        val params = mutableMapOf(
            "method" to "track.scrobble",
            "api_key" to apiKey,
            "sk" to sessionKey
        )

        batch.forEachIndexed { index, item ->
            params["artist[$index]"] = item.artist
            params["track[$index]"] = item.title
            params["timestamp[$index]"] = item.timestamp.toString()
            item.album?.let { if (it.isNotBlank()) params["album[$index]"] = it }
            item.albumArtist?.let { if (it.isNotBlank()) params["albumArtist[$index]"] = it }
            item.durationSeconds?.let { if (it > 0) params["duration[$index]"] = it.toString() }
        }

        val sig = LastFmSigner.sign(params, apiSecret)
        params["api_sig"] = sig
        params["format"] = "json"

        executePost(params) { responseBody ->
            checkError(responseBody)
        }
    }

    private fun checkError(responseBody: String): LastFmResult<Unit> {
        return try {
            val element = json.parseToJsonElement(responseBody)
            val obj = element.jsonObject
            if (obj.containsKey("error")) {
                val errorCode = obj["error"]?.jsonPrimitive?.content?.toIntOrNull()
                val message = obj["message"]?.jsonPrimitive?.content ?: "Unknown Last.fm error"
                LastFmResult.Error(errorCode, message)
            } else {
                LastFmResult.Success(Unit)
            }
        } catch (e: Exception) {
            LastFmResult.Error(null, "Failed to parse Last.fm response", e)
        }
    }

    private fun <T> executePost(
        params: Map<String, String>,
        parser: (String) -> LastFmResult<T>
    ): LastFmResult<T> {
        val formBuilder = FormBody.Builder()
        params.forEach { (key, value) ->
            formBuilder.add(key, value)
        }
        val request = Request.Builder()
            .url(baseUrl)
            .post(formBuilder.build())
            .build()

        return try {
            val response = client.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()
            parser(bodyString)
        } catch (e: IOException) {
            LastFmResult.Error(null, "Network error: ${e.message}", e)
        } catch (e: Exception) {
            LastFmResult.Error(null, "Request failed: ${e.message}", e)
        }
    }
}
