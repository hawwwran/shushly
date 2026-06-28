package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.model.Decision

/**
 * Turns a notification the user is correcting into a short, generalised, no-PII topic phrase (a
 * "digest"), used by behavior-steering. Throws if the AI is unavailable so the caller can surface an
 * error and save nothing. An interface so the ViewModel can be unit-tested with a fake.
 */
interface LearningDigester {
    suspend fun digest(
        appLabel: String,
        title: String?,
        body: String?,
        category: String?,
        desiredDecision: Decision,
    ): String
}
