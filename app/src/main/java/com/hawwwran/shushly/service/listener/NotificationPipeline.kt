package com.hawwwran.shushly.service.listener

import android.service.notification.StatusBarNotification
import android.util.Log
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.CriticalAlert
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import com.hawwwran.shushly.core.model.ExtractedNotification
import com.hawwwran.shushly.core.policy.ProtectedSourcePolicy
import com.hawwwran.shushly.service.ai.AiClassifier
import com.hawwwran.shushly.service.alerting.CriticalAlertNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
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
    val wasAlertPosted: Boolean,
)

/**
 * The decision pipeline (spec §7.2): eligibility → protected-source → text → dedupe →
 * classify → threshold → simulation → re-alert. Fails safe to silent (§3.4).
 *
 * An important notification (ALERT at/above threshold) sounds immediately, with its own
 * per-source-notification id, so distinct important notifications each alert. The only audible
 * rate limit is a global anti-storm backstop (see [DedupeRateLimiter]).
 */
@Singleton
class NotificationPipeline @Inject constructor(
    private val settings: SettingsRepository,
    private val extractor: NotificationContentExtractor,
    private val eligibility: NotificationEligibilityEvaluator,
    private val dedupe: DedupeRateLimiter,
    private val classifier: AiClassifier,
    private val notifier: CriticalAlertNotifier,
) {
    private val _log = MutableStateFlow<List<DecisionLogEntry>>(emptyList())
    val log: StateFlow<List<DecisionLogEntry>> = _log.asStateFlow()

    // Source notification keys we've posted an alert for, so a source dismissal can cancel our
    // mirror (spec §5). notifId == sourceKey.hashCode(). Thread-safe set.
    private val postedSourceKeys = ConcurrentHashMap.newKeySet<String>()

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
        val s = settings.snapshot()

        if (!s.smartQuietModeEnabled) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_QUIET_MODE_OFF, "Smart Quiet Mode is off", aiCalled = false, wasAlertPosted = false)
            return
        }
        if (ProtectedSourcePolicy.isProtected(e)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_PROTECTED_SOURCE, "Protected source", aiCalled = false, wasAlertPosted = false)
            return
        }
        if (e.isGroupSummary && e.body == null) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SILENT_GROUP_SUMMARY, null, aiCalled = false, wasAlertPosted = false)
            return
        }
        if (!eligibility.isEligible(e.packageName, s.eligibilityMode, s.selectedPackages)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_NOT_ELIGIBLE, null, aiCalled = false, wasAlertPosted = false)
            return
        }
        if (!extractor.hasUsableText(e)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_NO_USABLE_TEXT, null, aiCalled = false, wasAlertPosted = false)
            return
        }
        if (!dedupe.canCallAi(e.notificationKey)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_DUPLICATE, null, aiCalled = false, wasAlertPosted = false)
            return
        }

        val result = try {
            classifier.classify(e.toRequest())
        } catch (t: Throwable) {
            Log.w(TAG, "classify failed", t)
            record(e, Decision.ERROR, DecisionReasonCode.ERROR_AI_UNAVAILABLE, null, aiCalled = true, wasAlertPosted = false)
            return
        }

        val shouldAlert = result.decision == Decision.ALERT && result.confidence >= ALERT_THRESHOLD
        if (!shouldAlert) {
            record(e, Decision.SILENT, result.reasonCode, result.userVisibleReason, aiCalled = true, wasAlertPosted = false)
            return
        }
        if (s.simulationModeEnabled) {
            record(e, Decision.WOULD_ALERT, result.reasonCode, result.userVisibleReason, aiCalled = true, wasAlertPosted = false)
            return
        }

        // Important: sound immediately, unless the global anti-storm backstop is tripped.
        if (!dedupe.tryConsumeGlobalAlertSlot()) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_RATE_LIMIT, "Too many alerts just now — held back.", aiCalled = true, wasAlertPosted = false)
            return
        }

        val alert = CriticalAlert(
            sourcePackage = e.packageName,
            sourceLabel = e.appLabel,
            titleLine = "${e.appLabel}: ${e.title ?: e.body ?: "Notification"}",
            reasonLine = result.userVisibleReason ?: "Marked critical.",
            sourceContentIntent = e.contentIntent,
            vibrate = s.vibrateForCriticalAlerts,
        )
        notifier.post(alert, e.notificationKey.hashCode())
        postedSourceKeys.add(e.notificationKey)
        record(e, Decision.ALERT, result.reasonCode, result.userVisibleReason, aiCalled = true, wasAlertPosted = true)
    }

    /** A source notification was removed: mirror it by cancelling our alert for that key (spec §5). */
    fun onSourceRemoved(sourceKey: String) {
        if (postedSourceKeys.remove(sourceKey)) {
            runCatching { notifier.cancel(sourceKey.hashCode()) }
                .onFailure { Log.w(TAG, "cancel mirror failed", it) }
        }
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
        wasAlertPosted: Boolean,
    ) {
        Log.i(TAG, "${e.packageName} -> $decision ($reasonCode) ai=$aiCalled posted=$wasAlertPosted")
        val entry = DecisionLogEntry(
            timeMs = System.currentTimeMillis(),
            appLabel = e.appLabel,
            packageName = e.packageName,
            decision = decision,
            reasonCode = reasonCode,
            userVisibleReason = userVisibleReason,
            aiCalled = aiCalled,
            wasAlertPosted = wasAlertPosted,
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
