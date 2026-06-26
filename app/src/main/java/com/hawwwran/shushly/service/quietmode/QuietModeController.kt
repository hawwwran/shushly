package com.hawwwran.shushly.service.quietmode

import com.hawwwran.shushly.core.model.DecisionReasonCode
import kotlinx.coroutines.flow.StateFlow

/** Controls the app-owned quiet state (spec §5.4). */
interface QuietModeController {
    suspend fun enable(): QuietModeResult
    suspend fun disable(): QuietModeResult
    fun observeState(): StateFlow<QuietModeState>

    /** Recompute observable state from the system (e.g. after returning from settings). */
    fun refresh()
}

sealed interface QuietModeResult {
    data object Success : QuietModeResult
    data class Unavailable(val reason: DecisionReasonCode) : QuietModeResult
}

data class QuietModeState(
    val policyAccessGranted: Boolean = false,
    val ruleRegistered: Boolean = false,
    val active: Boolean = false,
)
