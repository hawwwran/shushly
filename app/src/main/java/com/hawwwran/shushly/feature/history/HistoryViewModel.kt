package com.hawwwran.shushly.feature.history

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

    fun clearHistory() {
        viewModelScope.launch { repository.clearAll() }
    }

    suspend fun getById(id: Long): DecisionHistoryEntity? = repository.getById(id)
}
