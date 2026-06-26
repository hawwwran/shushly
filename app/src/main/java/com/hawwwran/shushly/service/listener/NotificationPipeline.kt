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
import javax.inject.Inject
import javax.inject.Singleton

/** One recent decision, surfaced in the spike UI so the acceptance test is observable. */
data class DecisionLogEntry(
    val timeMs: Long,
    val appLabel: String,
    val packageName: String,
    val decision: Decision,
    val reasonCode: DecisionReasonCode,
    val userVisibleReason: String?,
    val wasAlertPosted: Boolean,
)

/**
 * The decision pipeline (spec §7.2): eligibility → protected-source → text → dedupe →
 * classify → threshold → simulation → cooldown → re-alert. Fails safe to silent (§3.4).
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

    suspend fun process(sbn: StatusBarNotification, appLabel: String) {
        val extracted = extractor.extract(sbn, appLabel) ?: return
        processExtracted(extracted)
    }

    /** Drives a synthesized notification through the real pipeline (debug trigger, spec §8.8). */
    suspend fun debugFire(triggerText: String) {
        processExtracted(
            ExtractedNotification(
                notificationKey = "debug-${System.nanoTime()}",
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
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_QUIET_MODE_OFF, "Smart Quiet Mode is off", false)
            return
        }
        if (ProtectedSourcePolicy.isProtected(e)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_PROTECTED_SOURCE, "Protected source", false)
            return
        }
        if (e.isGroupSummary && e.body == null) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SILENT_GROUP_SUMMARY, null, false)
            return
        }
        if (!eligibility.isEligible(e.packageName, s.eligibilityMode, s.selectedPackages)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_NOT_ELIGIBLE, null, false)
            return
        }
        if (!extractor.hasUsableText(e)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_NO_USABLE_TEXT, null, false)
            return
        }
        if (!dedupe.canCallAi(e.notificationKey)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_DUPLICATE, null, false)
            return
        }

        val result = try {
            classifier.classify(e.toRequest())
        } catch (t: Throwable) {
            Log.w(TAG, "classify failed", t)
            record(e, Decision.ERROR, DecisionReasonCode.ERROR_AI_UNAVAILABLE, null, false)
            return
        }

        val shouldAlert = result.decision == Decision.ALERT && result.confidence >= ALERT_THRESHOLD
        if (!shouldAlert) {
            record(e, Decision.SILENT, result.reasonCode, result.userVisibleReason, false)
            return
        }
        if (s.simulationModeEnabled) {
            record(e, Decision.WOULD_ALERT, result.reasonCode, result.userVisibleReason, false)
            return
        }
        if (!dedupe.canAlert(e.packageName)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_COOLDOWN, "Recent alert from this app", false)
            return
        }

        notifier.post(
            CriticalAlert(
                sourcePackage = e.packageName,
                sourceLabel = e.appLabel,
                titleLine = "${e.appLabel}: ${e.title ?: e.body ?: "Notification"}",
                reasonLine = result.userVisibleReason ?: "Marked critical.",
                sourceContentIntent = e.contentIntent,
                vibrate = s.vibrateForCriticalAlerts,
            ),
        )
        record(e, Decision.ALERT, result.reasonCode, result.userVisibleReason, true)
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
        wasAlertPosted: Boolean,
    ) {
        Log.i(TAG, "${e.packageName} -> $decision ($reasonCode) posted=$wasAlertPosted")
        val entry = DecisionLogEntry(
            timeMs = System.currentTimeMillis(),
            appLabel = e.appLabel,
            packageName = e.packageName,
            decision = decision,
            reasonCode = reasonCode,
            userVisibleReason = userVisibleReason,
            wasAlertPosted = wasAlertPosted,
        )
        _log.update { (listOf(entry) + it).take(MAX_LOG) }
    }

    companion object {
        const val ALERT_THRESHOLD = 0.80
        private const val MAX_LOG = 50
        private const val TAG = "ShushlyPipeline"
        private const val DEMO_PACKAGE = "com.demo.testapp"
        private const val DEMO_LABEL = "Demo App"
    }
}
