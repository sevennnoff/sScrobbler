package com.sscrobbler.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sscrobbler.app.database.HistoryDao
import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.lastfm.LastFmClient
import com.sscrobbler.app.lastfm.LastFmHistoryTrack
import com.sscrobbler.app.lastfm.LastFmResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class HistoryUiState(
    val items: List<LastFmHistoryTrack> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

class HistoryViewModel(
    private val historyDao: HistoryDao,
    private val authRepository: LastFmAuthRepository,
    private val lastFmClient: LastFmClient = LastFmClient()
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState(isLoading = true))
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            fetchFromLastFm()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)
            fetchFromLastFm()
        }
    }

    private suspend fun fetchFromLastFm() {
        val username = authRepository.getUsername()
        if (!username.isNullOrBlank()) {
            when (val res = lastFmClient.getRecentTracks(username, limit = 50)) {
                is LastFmResult.Success -> {
                    _uiState.value = HistoryUiState(
                        items = res.data,
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = null
                    )
                    return
                }
                is LastFmResult.Error -> {
                    // Fall back to local database
                }
            }
        }

        // Local fallback
        val localItems = historyDao.getRecent(50).map { entity ->
            LastFmHistoryTrack(
                id = entity.id,
                artist = entity.artist,
                title = entity.title,
                album = entity.album,
                timestamp = entity.timestamp,
                timeFormatted = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(entity.timestamp * 1000L)),
                isNowPlaying = false,
                artworkUrl = entity.artworkUrl
            )
        }

        _uiState.value = HistoryUiState(
            items = localItems,
            isLoading = false,
            isRefreshing = false,
            errorMessage = if (username.isNullOrBlank()) "Log in to Last.fm to see cloud history" else null
        )
    }

    fun clearLocalHistory() {
        viewModelScope.launch {
            historyDao.deleteAll()
            loadHistory()
        }
    }

    fun clearHistory() = clearLocalHistory()

    fun deleteItem(id: String) {
        viewModelScope.launch {
            historyDao.deleteById(id)
            loadHistory()
        }
    }

    class Factory(
        private val historyDao: HistoryDao,
        private val authRepository: LastFmAuthRepository,
        private val lastFmClient: LastFmClient = LastFmClient()
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(
                historyDao = historyDao,
                authRepository = authRepository,
                lastFmClient = lastFmClient
            ) as T
        }
    }
}
