package com.sscrobbler.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sscrobbler.app.database.HistoryDao
import com.sscrobbler.app.database.HistoryItemEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val items: List<HistoryItemEntity> = emptyList(),
    val isLoading: Boolean = false
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

class HistoryViewModel(
    private val historyDao: HistoryDao
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = historyDao.getRecentFlow(500)
        .map { items -> HistoryUiState(items = items, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = HistoryUiState(isLoading = true)
        )

    fun clearHistory() {
        viewModelScope.launch {
            historyDao.deleteAll()
        }
    }

    fun deleteItem(id: String) {
        viewModelScope.launch {
            historyDao.deleteById(id)
        }
    }

    class Factory(
        private val historyDao: HistoryDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(historyDao) as T
        }
    }
}
