package com.hawwwran.shushly.core.model

import java.time.Instant

/** Outcome of evaluating a single notification. */
enum class Decision { ALERT, SILENT, SKIPPED, ERROR, WOULD_ALERT }

/** Stable reason codes for every decision and skip path (spec §15.1). */
enum class DecisionReasonCode {
    ALERT_TIME_SENSITIVE_ACTION,
    ALERT_SAFETY_OR_EMERGENCY,
    ALERT_WORK_INCIDENT,
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
    SKIPPED_RATE_LIMIT,
    ERROR_PERMISSION_MISSING,
    ERROR_AI_UNAVAILABLE,
    ERROR_NETWORK,
    ERROR_INVALID_RESPONSE,
    ERROR_QUIET_MODE_UNAVAILABLE,
}

/** What the classifier is asked about (already redacted/normalized). */
data class ClassificationRequest(
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val body: String?,
    val category: String?,
    val postedAt: Instant,
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
