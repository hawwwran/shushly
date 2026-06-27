package com.hawwwran.shushly.feature.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawwwran.shushly.core.data.DecisionHistoryRepository
import com.hawwwran.shushly.core.data.db.DecisionHistoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the Decision history list and the Decision detail screen (Room-backed). */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: DecisionHistoryRepository,
) : ViewModel() {

    val entries: StateFlow<List<DecisionHistoryEntity>> = repository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The entry shown on the detail screen. Re-read from Room so feedback survives re-opening. */
    var detailEntry by mutableStateOf<DecisionHistoryEntity?>(null)
        private set

    fun clearHistory() {
        viewModelScope.launch { repository.clearAll() }
    }

    fun loadDetail(id: Long) {
        viewModelScope.launch { detailEntry = repository.getById(id) }
    }

    /** Persist local-only feedback (§14.3), then refresh so the selection reflects what was stored. */
    fun setFeedback(id: Long, feedback: String?) {
        viewModelScope.launch {
            repository.setFeedback(id, feedback)
            detailEntry = repository.getById(id)
        }
    }
}
