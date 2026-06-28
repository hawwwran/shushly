package com.hawwwran.shushly.feature.history

import com.hawwwran.shushly.core.data.db.DecisionHistoryEntity
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Shared rendering of a stored decision row, used by the list and the detail screen. */

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

internal fun DecisionHistoryEntity.decisionOrNull(): Decision? =
    runCatching { Decision.valueOf(decision) }.getOrNull()

internal fun DecisionHistoryEntity.reasonOrNull(): DecisionReasonCode? =
    runCatching { DecisionReasonCode.valueOf(reasonCode) }.getOrNull()

internal fun formatTime(epochMs: Long): String = timeFormatter.format(Instant.ofEpochMilli(epochMs))

internal fun formatDateTime(epochMs: Long): String = dateTimeFormatter.format(Instant.ofEpochMilli(epochMs))

internal fun decisionLabel(entity: DecisionHistoryEntity): String = when (entity.decisionOrNull()) {
    Decision.ALERT -> "ALERT"
    Decision.SILENT -> "SILENT"
    Decision.SKIPPED -> "SKIPPED"
    Decision.ERROR -> "ERROR"
    null -> entity.decision
}

/** Renders one entry as a lifecycle: seen -> eligibility -> AI call -> decision. */
internal fun lifecycleText(entity: DecisionHistoryEntity): String {
    val reason = entity.reasonOrNull()
    // Always-alert apps sound without ever calling the AI.
    if (reason == DecisionReasonCode.ALERT_ALWAYS) {
        return "seen → always-alert app → sounded (no AI)"
    }
    if (!entity.aiCalled) {
        val why = when (reason) {
            DecisionReasonCode.SKIPPED_QUIET_MODE_OFF -> "Smart Quiet Mode off"
            DecisionReasonCode.SKIPPED_PHONE_IN_USE -> "phone in use — stood aside"
            DecisionReasonCode.SKIPPED_PROTECTED_SOURCE -> "protected source"
            DecisionReasonCode.SKIPPED_NOT_ELIGIBLE -> "not eligible"
            DecisionReasonCode.SKIPPED_NO_USABLE_TEXT -> "no usable text"
            DecisionReasonCode.SKIPPED_DUPLICATE -> "duplicate (AI cooldown)"
            DecisionReasonCode.SKIPPED_RATE_LIMIT -> "too many alerts (held back)"
            DecisionReasonCode.SILENT_GROUP_SUMMARY -> "group summary"
            else -> entity.reasonCode.lowercase().replace('_', ' ')
        }
        return "seen → stopped: $why → no AI call"
    }
    val outcome = when (entity.decisionOrNull()) {
        Decision.ALERT -> if (entity.wasAlerted) "ALERT → sounded" else "ALERT"
        Decision.SILENT -> "SILENT"
        Decision.ERROR -> if (entity.wasAlerted) "ERROR → sounded by default" else "ERROR → stayed silent"
        Decision.SKIPPED ->
            if (reason == DecisionReasonCode.SKIPPED_RATE_LIMIT) "ALERT but rate-limited (held back)"
            else "skipped"
        null -> entity.decision
    }
    return "seen → eligible → AI called → $outcome"
}
