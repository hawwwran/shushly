package com.hawwwran.shushly.feature.history

import com.hawwwran.shushly.core.data.db.DecisionHistoryEntity
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import com.hawwwran.shushly.core.model.EligibilityMode

/**
 * Pure decision logic for the "steer Shushly" section of the decision-detail screen. Given a recorded
 * decision and the current settings, it decides which single AI-correction (if any) and which
 * list-management actions to offer. Kept free of Android/Compose so it can be unit-tested on the JVM.
 */

/** The corrective AI action for a row the AI actually judged (the prominent purple button). */
enum class SmartCorrection { SHOULD_ALERT, SHOULD_SILENT }

/** A list-management action; intent-based and mode-aware (the label is resolved with the app name). */
enum class ConfigAction { ADD_ALWAYS_ALERT, REMOVE_ALWAYS_ALERT, MAKE_ELIGIBLE, SILENCE_APP }

data class Steering(
    /** The smart correction to offer, or null when the AI never judged this row. */
    val smart: SmartCorrection?,
    /** Whether the smart button can act now: content still cached AND the AI is usable. */
    val smartActionable: Boolean,
    /** A learning already exists for this row — show the "saved"/undo state even past the 12 h window. */
    val alreadyCorrected: Boolean,
    val configActions: List<ConfigAction>,
    /** Short note when nothing is actionable (protected/structural skips). */
    val explanation: String?,
) {
    /** The smart button shows if there's a correction to make and it can act (or was already made). */
    val showSmart: Boolean get() = smart != null && (smartActionable || alreadyCorrected)
    val hasAnything: Boolean get() = showSmart || configActions.isNotEmpty() || explanation != null
}

fun steeringFor(
    entry: DecisionHistoryEntity,
    settings: AppSettings,
    hasCachedContent: Boolean,
    hasExistingLearning: Boolean,
    aiUsable: Boolean,
): Steering {
    val decision = entry.decisionOrNull()
    val reason = entry.reasonOrNull()
    val pkg = entry.packageName
    val inAlways = pkg in settings.alwaysAlertPackages
    val eligible = eligibleNow(pkg, settings)

    // The single corrective AI action, only when the AI actually judged this notification.
    val smart = when {
        !entry.aiCalled -> null
        decision == Decision.SILENT -> SmartCorrection.SHOULD_ALERT
        decision == Decision.ALERT && reason != DecisionReasonCode.ALERT_ALWAYS -> SmartCorrection.SHOULD_SILENT
        decision == Decision.ERROR -> SmartCorrection.SHOULD_SILENT // fail-safe sounded
        else -> null
    }

    val candidates = LinkedHashSet<ConfigAction>()
    var explanation: String? = null
    when {
        reason == DecisionReasonCode.ALERT_ALWAYS -> candidates += ConfigAction.REMOVE_ALWAYS_ALERT
        reason == DecisionReasonCode.SKIPPED_PROTECTED_SOURCE ->
            explanation = "Calls, alarms and security codes always come through — Shushly never changes these."
        reason == DecisionReasonCode.SKIPPED_NOT_ELIGIBLE -> {
            candidates += ConfigAction.MAKE_ELIGIBLE
            candidates += ConfigAction.ADD_ALWAYS_ALERT
        }
        smart == SmartCorrection.SHOULD_ALERT -> candidates += ConfigAction.ADD_ALWAYS_ALERT
        smart == SmartCorrection.SHOULD_SILENT -> candidates += ConfigAction.SILENCE_APP
        else -> explanation = explainSkip(reason)
    }

    val configActions = candidates.filter { it.isValid(inAlways, eligible) }

    return Steering(
        smart = smart,
        smartActionable = hasCachedContent && aiUsable,
        alreadyCorrected = hasExistingLearning,
        configActions = configActions,
        explanation = explanation,
    )
}

fun SmartCorrection.label(): String = when (this) {
    SmartCorrection.SHOULD_ALERT -> "This should have alerted"
    SmartCorrection.SHOULD_SILENT -> "This should have stayed silent"
}

fun ConfigAction.label(appLabel: String): String = when (this) {
    ConfigAction.ADD_ALWAYS_ALERT -> "Always alert for $appLabel"
    ConfigAction.REMOVE_ALWAYS_ALERT -> "Stop always-alerting $appLabel"
    ConfigAction.MAKE_ELIGIBLE -> "Let the AI decide for $appLabel"
    ConfigAction.SILENCE_APP -> "Silence $appLabel"
}

private fun ConfigAction.isValid(inAlways: Boolean, eligible: Boolean): Boolean = when (this) {
    ConfigAction.ADD_ALWAYS_ALERT -> !inAlways
    ConfigAction.REMOVE_ALWAYS_ALERT -> inAlways
    ConfigAction.MAKE_ELIGIBLE -> !eligible && !inAlways
    ConfigAction.SILENCE_APP -> eligible && !inAlways
}

/**
 * True when Shushly will never sound for [pkg] under the current settings: it is neither on the
 * always-alert list nor eligible for AI classification. The history list dims such rows so the user
 * can tell at a glance which apps still reach them and which are effectively silenced.
 */
fun isAlwaysSilenced(pkg: String, settings: AppSettings): Boolean =
    pkg !in settings.alwaysAlertPackages && !eligibleNow(pkg, settings)

/** Mirrors [com.hawwwran.shushly.service.listener.NotificationEligibilityEvaluator]. */
private fun eligibleNow(pkg: String, s: AppSettings): Boolean {
    val inList = pkg in s.selectedPackages
    return when (s.eligibilityMode) {
        EligibilityMode.SELECTED_APPS -> inList
        EligibilityMode.ALL_APPS_EXCEPT_SELECTED -> !inList
    }
}

private fun explainSkip(reason: DecisionReasonCode?): String? = when (reason) {
    DecisionReasonCode.SKIPPED_QUIET_MODE_OFF -> "Smart Quiet Mode was off, so Shushly stood aside."
    DecisionReasonCode.SKIPPED_DEAD_SILENT -> "Dead silent was on — everything was muted."
    DecisionReasonCode.SKIPPED_PHONE_IN_USE -> "You were using your phone, so Shushly stood aside."
    DecisionReasonCode.SKIPPED_NO_USABLE_TEXT -> "There was no text for the AI to judge."
    DecisionReasonCode.SILENT_GROUP_SUMMARY -> "This was a group summary with no content of its own."
    DecisionReasonCode.SKIPPED_DUPLICATE -> "A repeat of a notification seen moments earlier."
    DecisionReasonCode.SKIPPED_RATE_LIMIT -> "Held back briefly to avoid an alert storm."
    else -> null
}
