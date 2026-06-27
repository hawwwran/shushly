package com.hawwwran.shushly.service.listener

import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import org.junit.Assert.assertEquals
import org.junit.Test

class AiHealthTrackerTest {

    private fun action(
        current: Long?,
        decision: Decision,
        reason: DecisionReasonCode,
        aiCalled: Boolean,
    ) = AiHealthTracker.action(current, decision, reason, aiCalled)

    @Test
    fun aiError_whenClear_marksUnavailable() {
        assertEquals(
            AiHealthAction.MARK_UNAVAILABLE,
            action(null, Decision.ERROR, DecisionReasonCode.ERROR_AI_UNAVAILABLE, aiCalled = true),
        )
    }

    @Test
    fun aiError_whenAlreadyMarked_noChange() {
        assertEquals(
            AiHealthAction.NONE,
            action(123L, Decision.ERROR, DecisionReasonCode.ERROR_AI_UNAVAILABLE, aiCalled = true),
        )
    }

    @Test
    fun successfulAlert_whenMarked_clears() {
        assertEquals(
            AiHealthAction.CLEAR,
            action(123L, Decision.ALERT, DecisionReasonCode.ALERT_WORK_INCIDENT, aiCalled = true),
        )
    }

    @Test
    fun successfulSilent_whenMarked_clears() {
        assertEquals(
            AiHealthAction.CLEAR,
            action(123L, Decision.SILENT, DecisionReasonCode.SILENT_MARKETING, aiCalled = true),
        )
    }

    @Test
    fun wouldAlert_whenMarked_clears() {
        assertEquals(
            AiHealthAction.CLEAR,
            action(123L, Decision.WOULD_ALERT, DecisionReasonCode.ALERT_WORK_INCIDENT, aiCalled = true),
        )
    }

    @Test
    fun successfulClassify_whenAlreadyClear_noChange() {
        assertEquals(
            AiHealthAction.NONE,
            action(null, Decision.SILENT, DecisionReasonCode.SILENT_ROUTINE, aiCalled = true),
        )
    }

    @Test
    fun skip_withoutAiCall_neverChanges() {
        // A skip that never reached the AI must not clear a real outage flag.
        assertEquals(
            AiHealthAction.NONE,
            action(123L, Decision.SKIPPED, DecisionReasonCode.SKIPPED_QUIET_MODE_OFF, aiCalled = false),
        )
    }

    @Test
    fun nonAiError_doesNotMark() {
        // An error that isn't the AI-unavailable reason should not flip the AI flag.
        assertEquals(
            AiHealthAction.NONE,
            action(null, Decision.ERROR, DecisionReasonCode.ERROR_NETWORK, aiCalled = true),
        )
    }
}
