package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.ClassificationResult
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic development classifier (spec §8.8): text containing TEST_ALERT returns
 * alert, anything else (including TEST_SILENT) returns silent. The default binding in
 * debug builds; never the real path in release.
 */
@Singleton
class FakeAiClassifier @Inject constructor() : AiClassifier {
    override suspend fun classify(request: ClassificationRequest): ClassificationResult {
        val text = "${request.title.orEmpty()} ${request.body.orEmpty()}"
        return if (text.contains("TEST_ALERT")) {
            ClassificationResult(
                decision = Decision.ALERT,
                confidence = 0.95,
                reasonCode = DecisionReasonCode.ALERT_TIME_SENSITIVE_ACTION,
                userVisibleReason = "Requests action needed soon.",
                modelName = "fake",
                latencyMs = 0,
            )
        } else {
            ClassificationResult(
                decision = Decision.SILENT,
                confidence = 0.90,
                reasonCode = DecisionReasonCode.SILENT_ROUTINE,
                userVisibleReason = null,
                modelName = "fake",
                latencyMs = 0,
            )
        }
    }
}
