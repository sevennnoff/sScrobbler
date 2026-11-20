package com.sscrobbler.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sscrobbler.app.database.HistoryItemEntity
import com.sscrobbler.app.database.HistoryDao
import com.sscrobbler.app.lastfm.LastFmAuthRepository
import com.sscrobbler.app.lastfm.LastFmClient
import com.sscrobbler.app.lastfm.LastFmHistoryTrack
import com.sscrobbler.app.lastfm.LastFmResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class HistoryUiState(
    val items: List<LastFmHistoryTrack> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLocal: Boolean = false,
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

    val deviceHistory = historyDao.getRecentFlow(50).map { entries -> entries.map { it.asTrack() } }
    private var refreshJob: Job? = null

    init {
        loadHistory()
    }

    fun loadHistory() = requestHistory(refresh = false)

    fun refresh() = requestHistory(refresh = true)

    private fun requestHistory(refresh: Boolean) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = !refresh, isRefreshing = refresh, errorMessage = null)
            try {
                val username = authRepository.getUsername()
                val result = if (username.isNullOrBlank()) null else lastFmClient.getRecentTracks(username, 50)
                _uiState.value = when (result) {
                    is LastFmResult.Success -> HistoryUiState(items = result.data)
                    else -> HistoryUiState(
                        items = historyDao.getRecent(50).map { it.asTrack() },
                        isLocal = true,
                        errorMessage = if (username.isNullOrBlank()) "Connect Last.fm to see your profile history."
                            else "Last.fm is unavailable. Showing plays saved on this device."
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, isRefreshing = false,
                    errorMessage = "Couldn’t load history. Try refreshing.")
            }
        }
    }

    private fun HistoryItemEntity.asTrack() = LastFmHistoryTrack(
        id = id, artist = artist, title = title, album = album, timestamp = timestamp,
        timeFormatted = "", artworkUrl = artworkUrl, localStatus = status
    )

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
