package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import com.hawwwran.shushly.service.ai.relay.ClassifyResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure mapping from the relay response DTO to the app's ClassificationResult. */
class RelayResponseMappingTest {

    private fun response(decision: String, reason: String, confidence: Double = 0.9) =
        ClassifyResponseDto(decision = decision, confidence = confidence, reasonCode = reason)

    @Test
    fun alert_workIncident_mapsToAlert() {
        val r = response("alert", "ALERT_WORK_INCIDENT").toClassificationResult()
        assertEquals(Decision.ALERT, r.decision)
        assertEquals(DecisionReasonCode.ALERT_WORK_INCIDENT, r.reasonCode)
    }

    @Test
    fun silent_marketing_mapsToSilent() {
        val r = response("silent", "SILENT_MARKETING", confidence = 0.1).toClassificationResult()
        assertEquals(Decision.SILENT, r.decision)
        assertEquals(DecisionReasonCode.SILENT_MARKETING, r.reasonCode)
    }

    @Test
    fun unknownReasonCode_onAlert_defaultsToAlertReason_notSilent() {
        val r = response("alert", "BRAND_NEW_CODE").toClassificationResult()
        assertEquals(Decision.ALERT, r.decision)
        assertEquals(DecisionReasonCode.ALERT_TIME_SENSITIVE_ACTION, r.reasonCode)
        assertTrue("alert must not carry a SILENT_* reason", r.reasonCode.name.startsWith("ALERT_"))
    }

    @Test
    fun unknownReasonCode_onSilent_defaultsToSilentReason() {
        val r = response("silent", "???").toClassificationResult()
        assertEquals(Decision.SILENT, r.decision)
        assertEquals(DecisionReasonCode.SILENT_LOW_CONFIDENCE, r.reasonCode)
        assertTrue(r.reasonCode.name.startsWith("SILENT_"))
    }

    @Test
    fun knownCrossCategoryReason_onAlert_usesAlertDefault() {
        // decision=alert but a valid SILENT_* code: must not pass through.
        val r = response("alert", "SILENT_ROUTINE").toClassificationResult()
        assertEquals(Decision.ALERT, r.decision)
        assertEquals(DecisionReasonCode.ALERT_TIME_SENSITIVE_ACTION, r.reasonCode)
        assertTrue(r.reasonCode.name.startsWith("ALERT_"))
    }

    @Test
    fun knownCrossCategoryReason_onSilent_usesSilentDefault() {
        val r = response("silent", "ALERT_WORK_INCIDENT").toClassificationResult()
        assertEquals(Decision.SILENT, r.decision)
        assertEquals(DecisionReasonCode.SILENT_LOW_CONFIDENCE, r.reasonCode)
        assertTrue(r.reasonCode.name.startsWith("SILENT_"))
    }

    @Test(expected = IllegalStateException::class)
    fun invalidDecision_throws() {
        response("maybe", "SILENT_ROUTINE").toClassificationResult()
    }

    @Test
    fun confidence_passesThroughWithoutThresholding() {
        val r = response("alert", "ALERT_WORK_INCIDENT", confidence = 0.42).toClassificationResult()
        assertEquals(0.42, r.confidence, 0.0001)
        // Still ALERT despite < 0.80 — the pipeline owns the threshold, not the mapper.
        assertEquals(Decision.ALERT, r.decision)
    }
}
