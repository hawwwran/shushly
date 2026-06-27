package com.hawwwran.shushly.service.listener

import android.service.notification.StatusBarNotification
import android.util.Log
import com.hawwwran.shushly.core.data.DecisionHistoryRepository
import com.hawwwran.shushly.core.data.SeenAppsRepository
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.data.db.DecisionHistoryEntity
import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import com.hawwwran.shushly.core.model.ExtractedNotification
import com.hawwwran.shushly.core.policy.ProtectedSourcePolicy
import com.hawwwran.shushly.service.ai.AiClassifier
import com.hawwwran.shushly.service.alerting.CriticalAlertSounder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The decision pipeline (spec §7.2): eligibility → protected-source → text → dedupe →
 * classify → threshold → simulation → sound. Fails safe to silent (§3.4).
 *
 * An important notification (ALERT at/above threshold) plays a sound immediately (sound-only; no
 * notification is posted). The only audible rate limit is a global anti-storm backstop (see
 * [DedupeRateLimiter]).
 */
@Singleton
class NotificationPipeline @Inject constructor(
    private val settings: SettingsRepository,
    private val extractor: NotificationContentExtractor,
    private val eligibility: NotificationEligibilityEvaluator,
    private val dedupe: DedupeRateLimiter,
    private val classifier: AiClassifier,
    private val sounder: CriticalAlertSounder,
    private val seenApps: SeenAppsRepository,
    private val history: DecisionHistoryRepository,
) {

    suspend fun process(sbn: StatusBarNotification, appLabel: String) {
        val extracted = extractor.extract(sbn, appLabel) ?: return
        processExtracted(extracted)
    }

    /** Drives a synthesized notification through the real pipeline (debug trigger, spec §8.8). */
    suspend fun debugFire(triggerText: String) {
        processExtracted(
            ExtractedNotification(
                notificationKey = "$DEBUG_KEY_PREFIX${System.nanoTime()}",
                packageName = DEMO_PACKAGE,
                appLabel = DEMO_LABEL,
                postedAt = Instant.ofEpochMilli(System.currentTimeMillis()),
                title = DEMO_LABEL,
                body = triggerText,
                category = null,
                isOngoing = false,
                isGroupSummary = false,
                contentIntent = null,
            ),
        )
    }

    suspend fun processExtracted(e: ExtractedNotification) {
        // Learn which apps notify (feeds the picker's "Most used apps"), even while Quiet Mode is
        // off. Exclude the synthesized debug path so TEST_ALERT/TEST_SILENT don't pollute the list.
        if (!e.notificationKey.startsWith(DEBUG_KEY_PREFIX) && e.packageName != DEMO_PACKAGE) {
            seenApps.record(e.packageName)
        }

        val s = settings.snapshot()

        if (!s.smartQuietModeEnabled) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_QUIET_MODE_OFF, "Smart Quiet Mode is off", aiCalled = false, wasAlerted = false)
            return
        }
        if (ProtectedSourcePolicy.isProtected(e)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_PROTECTED_SOURCE, "Protected source", aiCalled = false, wasAlerted = false)
            return
        }
        // Always-alert: sound on every notification, bypassing the AI and the eligibility/text/dedupe/
        // group-summary checks. Still gated by simulation and the global anti-storm backstop. Protected
        // sources are checked first, so they always win.
        if (e.packageName in s.alwaysAlertPackages) {
            if (s.simulationModeEnabled) {
                record(e, Decision.WOULD_ALERT, DecisionReasonCode.ALERT_ALWAYS, "Always-alert (simulated)", aiCalled = false, wasAlerted = false)
                return
            }
            if (!dedupe.tryConsumeGlobalAlertSlot()) {
                record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_RATE_LIMIT, "Too many alerts just now — held back.", aiCalled = false, wasAlerted = false)
                return
            }
            sounder.playAlert(s.vibrateForCriticalAlerts, s.alertSoundUri, s.alertVolume)
            record(e, Decision.ALERT, DecisionReasonCode.ALERT_ALWAYS, "Always-alert: you set this app to always sound.", aiCalled = false, wasAlerted = true)
            return
        }
        if (e.isGroupSummary && e.body == null) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SILENT_GROUP_SUMMARY, null, aiCalled = false, wasAlerted = false)
            return
        }
        if (!eligibility.isEligible(e.packageName, s.eligibilityMode, s.selectedPackages)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_NOT_ELIGIBLE, null, aiCalled = false, wasAlerted = false)
            return
        }
        if (!extractor.hasUsableText(e)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_NO_USABLE_TEXT, null, aiCalled = false, wasAlerted = false)
            return
        }
        if (!dedupe.canCallAi(e.notificationKey)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_DUPLICATE, null, aiCalled = false, wasAlerted = false)
            return
        }

        val result = try {
            classifier.classify(e.toRequest())
        } catch (t: Throwable) {
            Log.w(TAG, "classify failed", t)
            record(e, Decision.ERROR, DecisionReasonCode.ERROR_AI_UNAVAILABLE, null, aiCalled = true, wasAlerted = false)
            return
        }

        val shouldAlert = result.decision == Decision.ALERT && result.confidence >= ALERT_THRESHOLD
        if (!shouldAlert) {
            record(e, Decision.SILENT, result.reasonCode, result.userVisibleReason, aiCalled = true, wasAlerted = false)
            return
        }
        if (s.simulationModeEnabled) {
            record(e, Decision.WOULD_ALERT, result.reasonCode, result.userVisibleReason, aiCalled = true, wasAlerted = false)
            return
        }

        // Important: sound immediately, unless the global anti-storm backstop is tripped.
        if (!dedupe.tryConsumeGlobalAlertSlot()) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_RATE_LIMIT, "Too many alerts just now — held back.", aiCalled = true, wasAlerted = false)
            return
        }

        sounder.playAlert(s.vibrateForCriticalAlerts, s.alertSoundUri, s.alertVolume)
        record(e, Decision.ALERT, result.reasonCode, result.userVisibleReason, aiCalled = true, wasAlerted = true)
    }

    private fun ExtractedNotification.toRequest() = ClassificationRequest(
        packageName = packageName,
        appLabel = appLabel,
        title = title,
        body = body,
        category = category,
        postedAt = postedAt,
    )

    private suspend fun record(
        e: ExtractedNotification,
        decision: Decision,
        reasonCode: DecisionReasonCode,
        userVisibleReason: String?,
        aiCalled: Boolean,
        wasAlerted: Boolean,
    ) {
        Log.i(TAG, "${e.packageName} -> $decision ($reasonCode) ai=$aiCalled alerted=$wasAlerted")
        val entity = DecisionHistoryEntity(
            createdAtMs = System.currentTimeMillis(),
            packageName = e.packageName,
            appLabel = e.appLabel,
            notificationKeyHash = e.notificationKey.hashCode().toString(),
            decision = decision.name,
            reasonCode = reasonCode.name,
            userVisibleReason = userVisibleReason,
            aiCalled = aiCalled,
            wasAlerted = wasAlerted,
        )
        // Resilient: a DB failure must never break the decision pipeline.
        runCatching { history.record(entity) }
            .onFailure { Log.w(TAG, "history insert failed", it) }

        updateAiHealth(decision, reasonCode, aiCalled)
    }

    /**
     * Track AI-call health for the Home banner (visibility only — never changes the decision). Writes
     * only on a transition: mark on an AI error, clear on a successful AI classify.
     */
    private suspend fun updateAiHealth(decision: Decision, reasonCode: DecisionReasonCode, aiCalled: Boolean) {
        val current = settings.snapshot().aiUnavailableSince
        when (AiHealthTracker.action(current, decision, reasonCode, aiCalled)) {
            AiHealthAction.MARK_UNAVAILABLE ->
                runCatching { settings.setAiUnavailableSince(System.currentTimeMillis()) }
            AiHealthAction.CLEAR ->
                runCatching { settings.setAiUnavailableSince(null) }
            AiHealthAction.NONE -> {}
        }
    }

    companion object {
        const val ALERT_THRESHOLD = 0.80
        const val DEBUG_KEY_PREFIX = "debug-"
        private const val TAG = "ShushlyPipeline"
        private const val DEMO_PACKAGE = "com.demo.testapp"
        private const val DEMO_LABEL = "Demo App"
    }
}
