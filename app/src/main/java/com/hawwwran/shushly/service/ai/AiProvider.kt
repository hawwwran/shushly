package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.ClassificationResult
import com.hawwwran.shushly.core.model.Decision

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

    /**
     * Summarise one notification the user is correcting into a short, generalised, no-PII topic phrase
     * (behavior-steering). Used on an explicit button tap, not in the hot classify path. Throws on any
     * failure so the caller can surface an error and save nothing.
     */
    suspend fun summarizeForLearning(
        appLabel: String,
        title: String?,
        body: String?,
        category: String?,
        desiredDecision: Decision,
        apiKey: String,
        model: String,
    ): String
}
