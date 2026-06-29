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
import com.hawwwran.shushly.service.quietmode.LockStateProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * The decision pipeline (spec §7.2): quiet-mode/in-use → protected-source → always-alert →
 * group-summary → eligibility → text → content-dedupe → key-dedupe → classify → threshold → sound.
 * When the AI can't classify an eligible notification it fails safe to *sound* (§3.4) — better an
 * unwanted alert than a missed-important one.
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
    private val lockState: LockStateProvider,
    private val contentCache: RecentNotificationContentCache,
) {

    suspend fun process(sbn: StatusBarNotification, appLabel: String) {
        val extracted = extractor.extract(sbn, appLabel) ?: return
        processExtracted(extracted)
    }

    suspend fun processExtracted(e: ExtractedNotification) {
        // "Static" notifications (ongoing or non-clearable: foreground-service icons, media controls,
        // download progress, "USB charging") are shown continuously rather than signalling an event.
        // They flood history and never warrant an alert, so Shushly ignores them outright — no AI, no
        // history, not even learned as a seen app.
        if (e.isPersistent) {
            Log.d(TAG, "${e.packageName}: ignoring static (persistent) notification")
            return
        }

        // Learn which apps notify (feeds the picker's "Most used apps"), even while Quiet Mode is off.
        seenApps.record(e.packageName)

        val s = settings.snapshot()

        if (!s.smartQuietModeEnabled) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_QUIET_MODE_OFF, "Smart Quiet Mode is off", aiCalled = false, wasAlerted = false)
            return
        }
        // Active-when-locked: while the phone is in use, Shushly fully stands aside (its zen rule is
        // off, so notifications behave normally) — no AI call, no re-alert stacked on top.
        if (s.activeWhenLocked && lockState.isInUse()) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_PHONE_IN_USE, "Phone in use — Shushly stood aside", aiCalled = false, wasAlerted = false)
            return
        }
        if (ProtectedSourcePolicy.isProtected(e)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_PROTECTED_SOURCE, "Protected source", aiCalled = false, wasAlerted = false)
            return
        }
        // Always-alert: sound on every notification, bypassing the AI and the eligibility/text/dedupe/
        // group-summary checks. Still gated by the global anti-storm backstop. Protected sources are
        // checked first, so they always win.
        if (e.packageName in s.alwaysAlertPackages) {
            if (!dedupe.tryConsumeGlobalAlertSlot()) {
                record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_RATE_LIMIT, "Too many alerts just now — held back.", aiCalled = false, wasAlerted = false)
                return
            }
            sounder.playAlert(s.vibrateForCriticalAlerts, s.alertSoundUri)
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
        // Content-level dedupe (spec §7.5): apps frequently re-post or rapidly update the same
        // notification (same app + identical text). Drop an exact repeat seen within the dedupe window
        // outright — no history row, no AI call, no re-alert — so duplicates don't flood the log. Placed
        // after the quiet-mode/in-use/protected/always-alert/eligibility gates: it must never swallow a
        // guaranteed-sound (protected or always-alert) notification, nor let a post seen while Quiet Mode
        // was off suppress the first real one once it's on. The 30s per-key cooldown below still records
        // same-key updates whose text *changed* (a fresh content hash) as SKIPPED_DUPLICATE.
        if (!dedupe.isFreshContent(e.contentHash)) {
            Log.d(TAG, "${e.packageName}: dropping duplicate (same content seen recently)")
            return
        }
        if (!dedupe.canCallAi(e.notificationKey)) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_DUPLICATE, null, aiCalled = false, wasAlerted = false)
            return
        }

        // Keep the raw content briefly (in-memory, 3 h) so the user can, from Decision history, ask
        // the AI to summarise this notification into a steering hint. Never persisted to disk.
        contentCache.put(e.notificationKey.hashCode().toString(), e.packageName, e.appLabel, e.title, e.body, e.category)

        val result = try {
            classifier.classify(e.toRequest())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Fail-safe to SOUND: an eligible notification we couldn't classify is treated as "AI said
            // alert" — better an unwanted sound than silently swallowing something important while the
            // AI is unreachable. Still gated by the global anti-storm backstop.
            Log.w(TAG, "classify failed; sounding by default", t)
            if (!dedupe.tryConsumeGlobalAlertSlot()) {
                record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_RATE_LIMIT, "Too many alerts just now — held back.", aiCalled = true, wasAlerted = false)
                return
            }
            sounder.playAlert(s.vibrateForCriticalAlerts, s.alertSoundUri)
            record(e, Decision.ERROR, DecisionReasonCode.ERROR_AI_UNAVAILABLE, "AI unavailable — sounded by default.", aiCalled = true, wasAlerted = true)
            return
        }

        val shouldAlert = result.decision == Decision.ALERT && result.confidence >= ALERT_THRESHOLD
        if (!shouldAlert) {
            record(e, Decision.SILENT, result.reasonCode, result.userVisibleReason, aiCalled = true, wasAlerted = false)
            return
        }

        // Important: sound immediately, unless the global anti-storm backstop is tripped.
        if (!dedupe.tryConsumeGlobalAlertSlot()) {
            record(e, Decision.SKIPPED, DecisionReasonCode.SKIPPED_RATE_LIMIT, "Too many alerts just now — held back.", aiCalled = true, wasAlerted = false)
            return
        }

        sounder.playAlert(s.vibrateForCriticalAlerts, s.alertSoundUri)
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
            contentHash = e.contentHash,
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
        private const val TAG = "ShushlyPipeline"
    }
}
