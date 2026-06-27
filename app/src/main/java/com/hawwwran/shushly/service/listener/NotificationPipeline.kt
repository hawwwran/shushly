package com.hawwwran.shushly.service.listener

import android.service.notification.StatusBarNotification
import android.util.Log
import com.hawwwran.shushly.core.data.SeenAppsRepository
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import com.hawwwran.shushly.core.model.ExtractedNotification
import com.hawwwran.shushly.core.policy.ProtectedSourcePolicy
import com.hawwwran.shushly.service.ai.AiClassifier
import com.hawwwran.shushly.service.alerting.CriticalAlertSounder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** One processed notification, surfaced as a lifecycle line in the Home processing log. */
data class DecisionLogEntry(
    val timeMs: Long,
    val appLabel: String,
    val packageName: String,
    val decision: Decision,
    val reasonCode: DecisionReasonCode,
    val userVisibleReason: String?,
    val aiCalled: Boolean,
    val wasAlerted: Boolean,
)

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
) {
    private val _log = MutableStateFlow<List<DecisionLogEntry>>(emptyList())
    val log: StateFlow<List<DecisionLogEntry>> = _log.asStateFlow()

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

        sounder.playAlert(s.vibrateForCriticalAlerts)
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

    private fun record(
        e: ExtractedNotification,
        decision: Decision,
        reasonCode: DecisionReasonCode,
        userVisibleReason: String?,
        aiCalled: Boolean,
        wasAlerted: Boolean,
    ) {
        Log.i(TAG, "${e.packageName} -> $decision ($reasonCode) ai=$aiCalled alerted=$wasAlerted")
        val entry = DecisionLogEntry(
            timeMs = System.currentTimeMillis(),
            appLabel = e.appLabel,
            packageName = e.packageName,
            decision = decision,
            reasonCode = reasonCode,
            userVisibleReason = userVisibleReason,
            aiCalled = aiCalled,
            wasAlerted = wasAlerted,
        )
        _log.update { (listOf(entry) + it).take(MAX_LOG) }
    }

    companion object {
        const val ALERT_THRESHOLD = 0.80
        const val MAX_LOG = 100
        const val DEBUG_KEY_PREFIX = "debug-"
        private const val TAG = "ShushlyPipeline"
        private const val DEMO_PACKAGE = "com.demo.testapp"
        private const val DEMO_LABEL = "Demo App"
    }
}
