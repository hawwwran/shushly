package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.ClassificationResult

/** Classifies a redacted notification. Implementations must fail safe to SILENT (spec §3.4). */
interface AiClassifier {
    suspend fun classify(request: ClassificationRequest): ClassificationResult
}
