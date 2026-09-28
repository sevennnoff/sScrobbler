package com.sscrobbler.app.lastfm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "lastfm_auth")

open class LastFmAuthRepository(private val dataStore: DataStore<Preferences>? = null) {

    companion object {
        val KEY_SESSION = stringPreferencesKey("lastfm_session_key")
        val KEY_USERNAME = stringPreferencesKey("lastfm_username")
        val KEY_AVATAR_URL = stringPreferencesKey("lastfm_avatar_url")
    }

    open val sessionKeyFlow: Flow<String?> = dataStore?.data?.map { preferences ->
        preferences[KEY_SESSION]
    } ?: emptyFlow()

    open val usernameFlow: Flow<String?> = dataStore?.data?.map { preferences ->
        preferences[KEY_USERNAME]
    } ?: emptyFlow()

    open val avatarUrlFlow: Flow<String?> = dataStore?.data?.map { preferences ->
        preferences[KEY_AVATAR_URL]
    } ?: emptyFlow()

    open val isLoggedInFlow: Flow<Boolean> = sessionKeyFlow.map { !it.isNullOrBlank() }

    open suspend fun getSessionKey(): String? {
        return sessionKeyFlow.first()
    }

    open suspend fun getUsername(): String? {
        return usernameFlow.first()
    }

    open suspend fun saveSession(username: String, sessionKey: String) {
        dataStore?.edit { preferences ->
            preferences[KEY_USERNAME] = username
            preferences[KEY_SESSION] = sessionKey
        }
    }

    open suspend fun saveAvatarUrl(url: String) {
        dataStore?.edit { preferences ->
            preferences[KEY_AVATAR_URL] = url
        }
    }

    open suspend fun clearSession() {
        dataStore?.edit { preferences ->
            preferences.remove(KEY_SESSION)
            preferences.remove(KEY_USERNAME)
            preferences.remove(KEY_AVATAR_URL)
        }
    }
}
