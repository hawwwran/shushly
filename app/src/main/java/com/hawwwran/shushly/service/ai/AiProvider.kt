package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.ClassificationResult

/**
 * A direct AI backend. Extensible: each provider (OpenAI today) implements classification with the
 * user's own key. Implementations must fail by throwing — the pipeline catches and falls safe to
 * silent (spec §3.4) — and must never log the key.
 */
interface AiProvider {
    suspend fun classify(
        request: ClassificationRequest,
        apiKey: String,
        model: String,
        userInstruction: String?,
    ): ClassificationResult
}
