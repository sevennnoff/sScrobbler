package com.sscrobbler.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

open class SettingsRepository(private val dataStore: DataStore<Preferences>? = null) {

    companion object {
        val KEY_MIN_LISTENED_PERCENT = intPreferencesKey("min_listened_percent")
        val KEY_MAX_REQUIRED_TIME_MS = longPreferencesKey("max_required_time_ms")
        val KEY_MIN_TRACK_DURATION_MS = longPreferencesKey("min_track_duration_ms")
        val KEY_PAUSE_TIMEOUT_MS = longPreferencesKey("pause_timeout_ms")
        val KEY_SEND_NOW_PLAYING = booleanPreferencesKey("send_now_playing")
        val KEY_PACKAGE_FILTER_JSON = stringPreferencesKey("package_filter_json")
    }

    private val json = Json { ignoreUnknownKeys = true }

    open val settingsFlow: Flow<AppSettings> = dataStore?.data?.map { preferences ->
        val percent = preferences[KEY_MIN_LISTENED_PERCENT] ?: 50
        val maxReqTime = preferences[KEY_MAX_REQUIRED_TIME_MS] ?: 240_000L
        val minDuration = preferences[KEY_MIN_TRACK_DURATION_MS] ?: 30_000L
        val pauseTimeout = preferences[KEY_PAUSE_TIMEOUT_MS] ?: 1_800_000L
        val sendNowPlaying = preferences[KEY_SEND_NOW_PLAYING] ?: true
        val filterJson = preferences[KEY_PACKAGE_FILTER_JSON]

        val filter: Map<String, Boolean> = if (!filterJson.isNullOrBlank()) {
            runCatching {
                json.decodeFromString<Map<String, Boolean>>(filterJson)
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        AppSettings(
            minListenedPercent = percent,
            maxRequiredTimeMs = maxReqTime,
            minTrackDurationMs = minDuration,
            pauseTimeoutMs = pauseTimeout,
            sendNowPlaying = sendNowPlaying,
            packageFilter = filter
        )
    } ?: emptyFlow()

    open suspend fun getSettings(): AppSettings = settingsFlow.first()

    open suspend fun updateMinListenedPercent(value: Int) {
        dataStore?.edit { it[KEY_MIN_LISTENED_PERCENT] = value }
    }

    open suspend fun updateMaxRequiredTimeMs(value: Long) {
        dataStore?.edit { it[KEY_MAX_REQUIRED_TIME_MS] = value }
    }

    open suspend fun updateMinTrackDurationMs(value: Long) {
        dataStore?.edit { it[KEY_MIN_TRACK_DURATION_MS] = value }
    }

    open suspend fun updatePauseTimeoutMs(value: Long) {
        dataStore?.edit { it[KEY_PAUSE_TIMEOUT_MS] = value }
    }

    open suspend fun updateSendNowPlaying(value: Boolean) {
        dataStore?.edit { it[KEY_SEND_NOW_PLAYING] = value }
    }

    open suspend fun setPackageAllowed(packageName: String, allowed: Boolean) {
        dataStore?.edit { preferences ->
            val filterJson = preferences[KEY_PACKAGE_FILTER_JSON]
            val filter = if (!filterJson.isNullOrBlank()) {
                runCatching {
                    json.decodeFromString<Map<String, Boolean>>(filterJson).toMutableMap()
                }.getOrDefault(mutableMapOf())
            } else {
                mutableMapOf()
            }
            filter[packageName] = allowed
            preferences[KEY_PACKAGE_FILTER_JSON] = json.encodeToString(filter)
        }
    }
}
