package com.sscrobbler.app.lastfm

import com.sscrobbler.app.database.PendingScrobbleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

open class LastFmClient(
    private val apiKey: String = com.sscrobbler.app.util.Secrets.getApiKey(),
    private val apiSecret: String = com.sscrobbler.app.util.Secrets.getApiSecret(),
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

    private val _connectionErrorFlow = MutableStateFlow<String?>(null)
    open val connectionErrorFlow: StateFlow<String?> = _connectionErrorFlow.asStateFlow()

    private fun parseLastFmError(body: String, httpCode: Int): LastFmResult.Error {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            try {
                val elem = json.parseToJsonElement(trimmed).jsonObject
                val code = elem["error"]?.jsonPrimitive?.content?.toIntOrNull()
                val msg = elem["message"]?.jsonPrimitive?.content
                if (msg != null || code != null) {
                    val userFriendlyMsg = when (code) {
                        4, 14 -> "Token not yet approved. Please tap 'Allow access' in your browser first."
                        9 -> "Invalid Last.fm session. Please log in again."
                        10 -> "Invalid API Key."
                        11, 16 -> "Last.fm service is temporarily unavailable. Please try again later."
                        26 -> "API Key suspended."
                        else -> msg ?: "Last.fm error (code $code)"
                    }
                    return LastFmResult.Error(code, userFriendlyMsg)
                }
            } catch (_: Exception) {}
        }

        // Check XML error <error code="4">Unauthorized Token</error>
        val xmlMatch = Regex("""<error\s+code="?(\d+)"?>([^<]+)</error>""").find(trimmed)
        if (xmlMatch != null) {
            val code = xmlMatch.groupValues[1].toIntOrNull()
            val msg = xmlMatch.groupValues[2].trim()
            val userFriendlyMsg = when (code) {
                4, 14 -> "Token not yet approved. Please tap 'Allow access' in your browser first."
                9 -> "Invalid Last.fm session. Please log in again."
                else -> msg
            }
            return LastFmResult.Error(code, userFriendlyMsg)
        }

        // Check HTML / Cloudflare
        if (trimmed.contains("<html", ignoreCase = true) || trimmed.contains("<!doctype", ignoreCase = true)) {
            val isCloudflare = trimmed.contains("cloudflare", ignoreCase = true) || trimmed.contains("just a moment", ignoreCase = true)
            val msg = if (isCloudflare) {
                "Last.fm is blocked by Cloudflare. Check your connection or disable VPN."
            } else {
                "Last.fm server error (HTTP $httpCode). Please check your internet or VPN."
            }
            return LastFmResult.Error(httpCode, msg)
        }

        if (trimmed.isBlank()) {
            return LastFmResult.Error(httpCode, "Empty response from Last.fm (HTTP $httpCode)")
        }

        return LastFmResult.Error(httpCode, "Last.fm error (HTTP $httpCode)")
    }

    private fun <T> executePost(
        params: Map<String, String>,
        parser: (String, Int) -> LastFmResult<T>
    ): LastFmResult<T> {
        val formBuilder = FormBody.Builder()
        params.forEach { (key, value) ->
            formBuilder.add(key, value)
        }
        val url = if (baseUrl.contains("?")) "$baseUrl&format=json" else "$baseUrl?format=json"
        val request = Request.Builder()
            .url(url)
            .post(formBuilder.build())
            .build()

        return try {
            val response = client.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                _connectionErrorFlow.value = "Last.fm error (HTTP ${response.code})"
            } else {
                _connectionErrorFlow.value = null
            }
            parser(bodyString, response.code)
        } catch (e: IOException) {
            _connectionErrorFlow.value = "Cannot reach Last.fm (check VPN / network)"
            LastFmResult.Error(null, "Network error: ${e.message}", e)
        } catch (e: Exception) {
            _connectionErrorFlow.value = "Cannot reach Last.fm (check VPN / network)"
            LastFmResult.Error(null, "Request failed: ${e.message}", e)
        }
    }

    private fun checkError(responseBody: String, httpCode: Int): LastFmResult<Unit> {
        val trimmed = responseBody.trim()
        if (trimmed.startsWith("{")) {
            try {
                val element = json.parseToJsonElement(trimmed)
                val obj = element.jsonObject
                if (obj.containsKey("error")) {
                    val err = parseLastFmError(trimmed, httpCode)
                    return LastFmResult.Error(err.code, err.message)
                } else {
                    return LastFmResult.Success(Unit)
                }
            } catch (_: Exception) {}
        }
        if (httpCode in 200..299 && !trimmed.contains("<error")) {
            return LastFmResult.Success(Unit)
        }
        val err = parseLastFmError(trimmed, httpCode)
        return LastFmResult.Error(err.code, err.message)
    }

    open suspend fun getToken(): LastFmResult<String> = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "method" to "auth.getToken",
            "api_key" to apiKey
        )
        val sig = LastFmSigner.sign(params, apiSecret)
        params["api_sig"] = sig
        params["format"] = "json"

        executePost(params) { responseBody, httpCode ->
            val trimmed = responseBody.trim()
            if (trimmed.startsWith("{")) {
                try {
                    val parsed = json.decodeFromString<LastFmTokenResponse>(trimmed)
                    if (!parsed.token.isNullOrBlank()) {
                        return@executePost LastFmResult.Success(parsed.token)
                    }
                } catch (_: Exception) {}
            }
            parseLastFmError(trimmed, httpCode)
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

        executePost(params) { responseBody, httpCode ->
            val trimmed = responseBody.trim()
            if (trimmed.startsWith("{")) {
                try {
                    val parsed = json.decodeFromString<LastFmSessionResponse>(trimmed)
                    if (parsed.session != null) {
                        return@executePost LastFmResult.Success(parsed.session)
                    }
                } catch (_: Exception) {}
            }
            parseLastFmError(trimmed, httpCode)
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

        executePost(params) { responseBody, httpCode ->
            checkError(responseBody, httpCode)
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

        executePost(params) { responseBody, httpCode ->
            checkError(responseBody, httpCode)
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

        executePost(params) { responseBody, httpCode ->
            checkError(responseBody, httpCode)
        }
    }

    private val artworkCache = mutableMapOf<String, String>()

    open suspend fun getTrackArtworkUrl(artist: String, track: String): String? = withContext(Dispatchers.IO) {
        val cacheKey = "${artist.lowercase().trim()}|${track.lowercase().trim()}"
        synchronized(artworkCache) {
            if (artworkCache.containsKey(cacheKey)) return@withContext artworkCache[cacheKey]
        }

        try {
            val url = "$baseUrl?method=track.getInfo&api_key=$apiKey&artist=${java.net.URLEncoder.encode(artist, "UTF-8")}&track=${java.net.URLEncoder.encode(track, "UTF-8")}&format=json"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty().trim()
            if (!response.isSuccessful || !body.startsWith("{")) return@withContext null

            val element = json.parseToJsonElement(body).jsonObject
            val trackObj = element["track"]?.jsonObject ?: return@withContext null
            val albumObj = trackObj["album"]?.jsonObject
            val imageArray = albumObj?.get("image")?.let {
                if (it is JsonArray) it else null
            }
            val rawUrl = imageArray?.lastOrNull()?.jsonObject?.get("#text")?.jsonPrimitive?.content
            if (!rawUrl.isNullOrBlank()) {
                val bigUrl = rawUrl.replace(Regex("/u/\\d+x\\d+/"), "/u/700x0/")
                synchronized(artworkCache) {
                    artworkCache[cacheKey] = bigUrl
                }
                return@withContext bigUrl
            }
        } catch (_: Exception) {
            // Ignored
        }
        null
    }

    open suspend fun getRecentTracks(user: String, limit: Int = 50): LastFmResult<List<LastFmHistoryTrack>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl?method=user.getRecentTracks&user=${java.net.URLEncoder.encode(user, "UTF-8")}&api_key=$apiKey&limit=$limit&format=json"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty().trim()
            if (!response.isSuccessful || !body.startsWith("{")) {
                val err = parseLastFmError(body, response.code)
                _connectionErrorFlow.value = err.message
                return@withContext LastFmResult.Error(err.code, err.message)
            }
            _connectionErrorFlow.value = null
            val root = json.parseToJsonElement(body).jsonObject
            val recenttracks = root["recenttracks"]?.jsonObject ?: return@withContext LastFmResult.Success(emptyList())
            val trackElement = recenttracks["track"] ?: return@withContext LastFmResult.Success(emptyList())

            val trackListJson = if (trackElement is JsonArray) {
                trackElement
            } else if (trackElement is kotlinx.serialization.json.JsonObject) {
                JsonArray(listOf(trackElement))
            } else {
                JsonArray(emptyList())
            }

            val list = mutableListOf<LastFmHistoryTrack>()
            for ((idx, elem) in trackListJson.withIndex()) {
                val obj = elem.jsonObject
                val artistObj = obj["artist"]
                val artistName = if (artistObj is kotlinx.serialization.json.JsonObject) {
                    artistObj["#text"]?.jsonPrimitive?.content.orEmpty()
                } else {
                    artistObj?.jsonPrimitive?.content.orEmpty()
                }
                val title = obj["name"]?.jsonPrimitive?.content.orEmpty()
                val albumObj = obj["album"]?.jsonObject
                val albumName = albumObj?.get("#text")?.jsonPrimitive?.content

                val attrObj = obj["@attr"]?.jsonObject
                val isNowPlaying = attrObj?.get("nowplaying")?.jsonPrimitive?.content == "true"

                val dateObj = obj["date"]?.jsonObject
                val uts = dateObj?.get("uts")?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis() / 1000
                val dateText = dateObj?.get("#text")?.jsonPrimitive?.content ?: if (isNowPlaying) "Scrobbling now" else "Just now"

                val images = obj["image"] as? JsonArray
                val imgLarge = images?.firstOrNull {
                    it.jsonObject["size"]?.jsonPrimitive?.content == "large"
                }?.jsonObject?.get("#text")?.jsonPrimitive?.content
                val imgMed = images?.firstOrNull {
                    it.jsonObject["size"]?.jsonPrimitive?.content == "medium"
                }?.jsonObject?.get("#text")?.jsonPrimitive?.content
                val chosenArt = (if (!imgLarge.isNullOrBlank()) imgLarge else imgMed)?.let {
                    if (it.isNotBlank()) it else null
                }

                if (artistName.isNotBlank() && title.isNotBlank()) {
                    list.add(
                        LastFmHistoryTrack(
                            id = "$uts-$idx",
                            artist = artistName,
                            title = title,
                            album = albumName,
                            timestamp = uts,
                            timeFormatted = dateText,
                            isNowPlaying = isNowPlaying,
                            artworkUrl = chosenArt
                        )
                    )
                }
            }
            LastFmResult.Success(list)
        } catch (e: Exception) {
            val err = parseLastFmError(e.message.orEmpty(), -1)
            LastFmResult.Error(err.code, "Failed to load recent tracks: ${e.message}", e)
        }
    }

    open suspend fun getUserAvatarUrl(username: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl?method=user.getInfo&user=${java.net.URLEncoder.encode(username, "UTF-8")}&api_key=$apiKey&format=json"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty().trim()
            if (!response.isSuccessful || !body.startsWith("{")) return@withContext null

            val element = json.parseToJsonElement(body).jsonObject
            val userObj = element["user"]?.jsonObject ?: return@withContext null
            val imageArray = userObj["image"]?.let {
                if (it is JsonArray) it else null
            }
            val rawUrl = imageArray?.lastOrNull()?.jsonObject?.get("#text")?.jsonPrimitive?.content
            if (!rawUrl.isNullOrBlank()) {
                return@withContext rawUrl.replace(Regex("/u/\\d+x\\d+/"), "/u/300x300/")
            }
        } catch (_: Exception) {
            // Ignored
        }
        null
    }
}
