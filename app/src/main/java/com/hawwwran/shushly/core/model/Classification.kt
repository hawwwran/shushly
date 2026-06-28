package com.hawwwran.shushly.core.model

import java.time.Instant

/** Outcome of evaluating a single notification. */
enum class Decision { ALERT, SILENT, SKIPPED, ERROR }

/** Stable reason codes for every decision and skip path (spec §15.1). */
enum class DecisionReasonCode {
    ALERT_TIME_SENSITIVE_ACTION,
    ALERT_SAFETY_OR_EMERGENCY,
    ALERT_WORK_INCIDENT,

    /** User put the app in "Always alert" — it sounds on every notification, bypassing the AI.
     *  Local-only; never a relay/OpenAI wire code. */
    ALERT_ALWAYS,

    SILENT_ROUTINE,
    SILENT_MARKETING,
    SILENT_GROUP_SUMMARY,
    SILENT_LOW_CONFIDENCE,
    SKIPPED_NOT_ELIGIBLE,
    SKIPPED_PROTECTED_SOURCE,
    SKIPPED_NO_USABLE_TEXT,
    SKIPPED_DUPLICATE,
    SKIPPED_COOLDOWN,
    SKIPPED_DAILY_LIMIT,
    SKIPPED_QUIET_MODE_OFF,
    SKIPPED_PHONE_IN_USE,
    SKIPPED_RATE_LIMIT,
    ERROR_PERMISSION_MISSING,
    ERROR_AI_UNAVAILABLE,
    ERROR_NETWORK,
    ERROR_INVALID_RESPONSE,
    ERROR_QUIET_MODE_UNAVAILABLE,
}

/**
 * One user-taught steering hint for a single app: "this kind of notification should [desiredDecision]".
 * [digest] is an AI-written, generalised, no-PII topic phrase (e.g. "extreme weather warning") — never
 * raw notification text. Created from a decision-history correction and replayed into every later
 * request for the same app so the AI improves as the user steers it.
 */
data class AppLearning(
    val desiredDecision: Decision,
    val digest: String,
)

/** What the classifier is asked about (already redacted/normalized). */
data class ClassificationRequest(
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val body: String?,
    val category: String?,
    val postedAt: Instant,
    /** The user's past corrections for this app, injected as advisory guidance (default: none). */
    val appLearnings: List<AppLearning> = emptyList(),
)

/** Validated classifier outcome. */
data class ClassificationResult(
    val decision: Decision,
    val confidence: Double,
    val reasonCode: DecisionReasonCode,
    val userVisibleReason: String?,
    val modelName: String?,
    val latencyMs: Long?,
)
