package com.hawwwran.shushly.service.listener

import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode

/** What to do with the persisted AI-unavailable flag after a finalized decision. */
enum class AiHealthAction { NONE, MARK_UNAVAILABLE, CLEAR }

/**
 * Pure transition for the AI-health flag (`aiUnavailableSince`). Visibility only — it never changes
 * the decision (AI errors still fail safe to silent). Writes happen only on a state transition:
 *  - mark when an AI call errors (ERROR / ERROR_AI_UNAVAILABLE) and the flag is currently clear;
 *  - clear when an AI call succeeds (aiCalled and decision in ALERT/SILENT) and it is set.
 * Skips (no AI call) and unchanged states yield [AiHealthAction.NONE].
 */
object AiHealthTracker {
    fun action(
        current: Long?,
        decision: Decision,
        reasonCode: DecisionReasonCode,
        aiCalled: Boolean,
    ): AiHealthAction {
        val isAiError = decision == Decision.ERROR && reasonCode == DecisionReasonCode.ERROR_AI_UNAVAILABLE
        val isAiSuccess = aiCalled && (decision == Decision.ALERT || decision == Decision.SILENT)
        return when {
            isAiError && current == null -> AiHealthAction.MARK_UNAVAILABLE
            isAiSuccess && current != null -> AiHealthAction.CLEAR
            else -> AiHealthAction.NONE
        }
    }
}
