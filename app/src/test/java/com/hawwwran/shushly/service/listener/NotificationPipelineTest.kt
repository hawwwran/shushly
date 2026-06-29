/*
 * Pipeline-level decision tests (spec §16.1): threshold, fail-to-silent, the anti-storm
 * backstop, vibrate propagation, and every skip branch. Pure JVM with recording test doubles — the
 * pure collaborators (extractor / eligibility / dedupe) are the real ones.
 *
 * Deferred: the Room 30-day purge (DecisionHistoryRepositoryImpl.purgeOlderThan -> DAO) needs a real
 * Room instance, i.e. Robolectric or an instrumented test; not added here (no Robolectric this task).
 */
package com.hawwwran.shushly.service.listener

import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.ClassificationResult
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import com.hawwwran.shushly.core.model.EligibilityMode
import com.hawwwran.shushly.core.model.ExtractedNotification
import com.hawwwran.shushly.service.ai.AiClassifier
import com.hawwwran.shushly.service.ai.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NotificationPipelineTest {

    private class Harness(
        settings: SettingsRepository,
        classifier: AiClassifier,
        val dedupe: DedupeRateLimiter = DedupeRateLimiter(),
        val lockState: FakeLockStateProvider = FakeLockStateProvider(inUse = false),
    ) {
        val sounder = RecordingSounder()
        val history = RecordingHistoryRepository()
        val seenApps = FakeSeenAppsRepository()
        val contentCache = FakeRecentNotificationContentCache()
        val pipeline = NotificationPipeline(
            settings = settings,
            extractor = NotificationContentExtractor(),
            eligibility = NotificationEligibilityEvaluator(),
            dedupe = dedupe,
            classifier = classifier,
            sounder = sounder,
            seenApps = seenApps,
            history = history,
            lockState = lockState,
            contentCache = contentCache,
        )
    }

    // --- Threshold (§8.6) ---

    @Test
    fun alertAtThreshold_recordsAlert_andSoundsOnce() = runTest {
        val h = Harness(settings(), ProgrammableClassifier(alert(0.80)))
        h.pipeline.processExtracted(extracted())

        assertEquals(Decision.ALERT.name, h.history.last?.decision)
        assertEquals(1, h.sounder.callCount)
        assertTrue(h.history.last!!.wasAlerted)
        assertTrue(h.history.last!!.aiCalled)
    }

    // --- Steering content cache: kept only for notifications that reach the AI ---

    @Test
    fun classifierCalled_cachesContentForSteering() = runTest {
        val h = Harness(settings(), ProgrammableClassifier(alert(0.80)))
        h.pipeline.processExtracted(extracted(key = "k"))
        assertEquals(listOf("k".hashCode().toString()), h.contentCache.puts)
    }

    @Test
    fun skippedBeforeAi_doesNotCacheContent() = runTest {
        val h = Harness(settings(smartQuiet = false), ProgrammableClassifier(alert(0.80)))
        h.pipeline.processExtracted(extracted(key = "k"))
        assertTrue(h.contentCache.puts.isEmpty())
    }

    @Test
    fun alertBelowThreshold_recordsSilent_notSounded() = runTest {
        val h = Harness(settings(), ProgrammableClassifier(alert(0.79)))
        h.pipeline.processExtracted(extracted())

        assertEquals(Decision.SILENT.name, h.history.last?.decision)
        assertEquals(0, h.sounder.callCount)
        assertFalse(h.history.last!!.wasAlerted)
        assertTrue(h.history.last!!.aiCalled)
    }

    // --- Fail-safe to sound (§3.4): an eligible notification the AI can't classify sounds by default ---

    @Test
    fun classifierThrows_soundsByDefault_recordsError() = runTest {
        val h = Harness(settings(), ProgrammableClassifier(error = RuntimeException("AI down")))
        h.pipeline.processExtracted(extracted())

        assertEquals(Decision.ERROR.name, h.history.last?.decision)
        assertEquals(DecisionReasonCode.ERROR_AI_UNAVAILABLE.name, h.history.last?.reasonCode)
        assertEquals(1, h.sounder.callCount)
        assertTrue(h.history.last!!.wasAlerted)
        assertTrue(h.history.last!!.aiCalled)
    }

    @Test
    fun classifierThrows_backstopTripped_recordsRateLimit_notSounded() = runTest {
        val h = Harness(settings(), ProgrammableClassifier(error = RuntimeException("AI down")))
        // Consume every global alert slot first, so the fail-safe sound is held back by the backstop.
        repeat(DedupeRateLimiter.MAX_ALERTS_PER_WINDOW) { assertTrue(h.dedupe.tryConsumeGlobalAlertSlot()) }

        h.pipeline.processExtracted(extracted())

        assertEquals(Decision.SKIPPED.name, h.history.last?.decision)
        assertEquals(DecisionReasonCode.SKIPPED_RATE_LIMIT.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
        assertTrue(h.history.last!!.aiCalled)
    }

    // --- Anti-storm backstop (§7.5) ---

    @Test
    fun antiStormBackstop_capsAudibleAlertsAtWindowMax() = runTest {
        val cap = DedupeRateLimiter.MAX_ALERTS_PER_WINDOW
        val h = Harness(settings(), ProgrammableClassifier(alert(0.95)))

        // Distinct content per notification so the content-dedupe lets them all through to the backstop.
        repeat(cap + 1) { i -> h.pipeline.processExtracted(extracted(key = "key-$i", body = "incident $i")) }

        assertEquals(cap, h.sounder.callCount)
        val records = h.history.recorded
        assertEquals(cap + 1, records.size)
        assertTrue(records.take(cap).all { it.decision == Decision.ALERT.name && it.wasAlerted })
        assertEquals(Decision.SKIPPED.name, records.last().decision)
        assertEquals(DecisionReasonCode.SKIPPED_RATE_LIMIT.name, records.last().reasonCode)
        assertFalse(records.last().wasAlerted)
    }

    // --- Vibrate flag propagation ---

    @Test
    fun vibrateFlag_propagatesToSounder() = runTest {
        val on = Harness(settings(vibrate = true), ProgrammableClassifier(alert(0.95)))
        on.pipeline.processExtracted(extracted())
        assertEquals(true, on.sounder.lastVibrate)

        val off = Harness(settings(vibrate = false), ProgrammableClassifier(alert(0.95)))
        off.pipeline.processExtracted(extracted())
        assertEquals(false, off.sounder.lastVibrate)
    }

    // --- Skip branches ---

    @Test
    fun persistentNotification_isIgnoredOutright_noHistory_noSeenApp_noAi() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(), classifier)
        h.pipeline.processExtracted(extracted(isPersistent = true))

        assertTrue(h.history.recorded.isEmpty())
        assertTrue(h.seenApps.recorded.isEmpty())
        assertEquals(0, h.sounder.callCount)
        assertEquals(0, classifier.callCount)
    }

    @Test
    fun smartQuietModeOff_skips_withoutCallingAi() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(smartQuiet = false), classifier)
        h.pipeline.processExtracted(extracted())

        assertEquals(Decision.SKIPPED.name, h.history.last?.decision)
        assertEquals(DecisionReasonCode.SKIPPED_QUIET_MODE_OFF.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
        assertFalse(h.history.last!!.aiCalled)
        assertEquals(0, classifier.callCount)
    }

    // --- Dead silent (total-silence override) ---

    @Test
    fun deadSilent_suppressesAlwaysAlert_noSound() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(deadSilent = true, always = setOf("com.example.app")), classifier)
        h.pipeline.processExtracted(extracted(pkg = "com.example.app"))

        assertEquals(DecisionReasonCode.SKIPPED_DEAD_SILENT.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
        assertEquals(0, classifier.callCount)
        assertFalse(h.history.last!!.wasAlerted)
    }

    @Test
    fun deadSilent_suppressesAiAlert_withoutCallingAi() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(deadSilent = true), classifier)
        h.pipeline.processExtracted(extracted())

        assertEquals(DecisionReasonCode.SKIPPED_DEAD_SILENT.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
        assertEquals(0, classifier.callCount) // AI is never consulted
    }

    @Test
    fun deadSilent_suppressesFailSafeSound() = runTest {
        // Even the fail-safe-to-sound path is suppressed: dead silent returns before classify is attempted.
        val h = Harness(settings(deadSilent = true), ProgrammableClassifier(error = RuntimeException("AI down")))
        h.pipeline.processExtracted(extracted())

        assertEquals(DecisionReasonCode.SKIPPED_DEAD_SILENT.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
    }

    @Test
    fun protectedSource_skips_withoutCallingAi() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(), classifier)
        // A protected package (dialer) — never analysed or re-alerted (§3.5).
        h.pipeline.processExtracted(extracted(pkg = "com.google.android.dialer"))

        assertEquals(DecisionReasonCode.SKIPPED_PROTECTED_SOURCE.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
        assertFalse(h.history.last!!.aiCalled)
        assertEquals(0, classifier.callCount)
    }

    @Test
    fun notEligible_skips_withoutCallingAi() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(
            settings(eligibilityMode = EligibilityMode.SELECTED_APPS, selected = emptySet()),
            classifier,
        )
        h.pipeline.processExtracted(extracted())

        assertEquals(DecisionReasonCode.SKIPPED_NOT_ELIGIBLE.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
        assertFalse(h.history.last!!.aiCalled)
        assertEquals(0, classifier.callCount)
    }

    @Test
    fun noUsableText_skips_withoutCallingAi() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(), classifier)
        h.pipeline.processExtracted(extracted(title = null, body = null))

        assertEquals(DecisionReasonCode.SKIPPED_NO_USABLE_TEXT.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
        assertFalse(h.history.last!!.aiCalled)
        assertEquals(0, classifier.callCount)
    }

    @Test
    fun groupSummaryWithNullBody_recordsSilentGroupSummary() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(), classifier)
        h.pipeline.processExtracted(extracted(isGroupSummary = true, body = null))

        assertEquals(Decision.SKIPPED.name, h.history.last?.decision)
        assertEquals(DecisionReasonCode.SILENT_GROUP_SUMMARY.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
        assertFalse(h.history.last!!.aiCalled)
        assertEquals(0, classifier.callCount)
    }

    @Test
    fun sameNotificationKeyTwice_secondIsSkippedDuplicate() = runTest {
        val h = Harness(settings(), ProgrammableClassifier(alert(0.95)))

        // Same key but changed text (e.g. a message-count update): content dedupe lets both through,
        // so the 30s per-key AI cooldown is what records the second as SKIPPED_DUPLICATE.
        h.pipeline.processExtracted(extracted(key = "dup", body = "first body"))
        h.pipeline.processExtracted(extracted(key = "dup", body = "second body"))

        val records = h.history.recorded
        assertEquals(2, records.size)
        assertEquals(Decision.ALERT.name, records[0].decision)
        assertTrue(records[0].wasAlerted)
        assertEquals(Decision.SKIPPED.name, records[1].decision)
        assertEquals(DecisionReasonCode.SKIPPED_DUPLICATE.name, records[1].reasonCode)
        assertFalse(records[1].aiCalled)
        assertEquals(1, h.sounder.callCount) // only the first sounded
    }

    @Test
    fun identicalContentTwice_secondDroppedOutright_noHistoryNoAi() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(), classifier)

        // Same app + identical title/body, re-posted under a different system key (as updates often are).
        // Posted back-to-back here, so the burst guard collapses the repeat (the 60-min content dedupe
        // would catch a later one); either way it never reaches history, the AI, or the sounder.
        h.pipeline.processExtracted(extracted(key = "k1"))
        h.pipeline.processExtracted(extracted(key = "k2"))

        assertEquals(1, h.history.recorded.size) // only the first instance is reported
        assertEquals(1, classifier.callCount) // the duplicate never reaches the AI
        assertEquals(1, h.sounder.callCount) // and never re-sounds
    }

    @Test
    fun recordedRow_carriesContentHash() = runTest {
        val h = Harness(settings(), ProgrammableClassifier(alert(0.95)))
        val e = extracted()
        h.pipeline.processExtracted(e)
        // The persisted hash is the same value the pipeline deduped on (shown in the detail screen).
        assertEquals(e.contentHash, h.history.last?.contentHash)
    }

    @Test
    fun recordedRow_carriesRawContentInDebugBuilds() = runTest {
        // DEBUG-ONLY diagnostic capture: the raw title/body is persisted in debug builds (unit tests run
        // the debug variant, so BuildConfig.DEBUG is true). Release builds leave these null.
        val h = Harness(settings(), ProgrammableClassifier(alert(0.95)))
        h.pipeline.processExtracted(extracted(title = "Hello", body = "World"))
        assertEquals("Hello", h.history.last?.debugTitle)
        assertEquals("World", h.history.last?.debugBody)
    }

    @Test
    fun whatsAppDoublePost_appNamePrefixedTitle_collapsesToOne() = runTest {
        // WhatsApp posts one message twice in the same instant: titled "WhatsApp: Alice" and "Alice".
        // The app-name prefix is stripped before hashing, so the two share a content hash and the burst
        // guard collapses them — one history row, one classify, one sound (instead of a duplicate).
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(), classifier)
        h.pipeline.processExtracted(extracted(key = "k1", appLabel = "WhatsApp", title = "WhatsApp: Alice", body = "see you"))
        h.pipeline.processExtracted(extracted(key = "k2", appLabel = "WhatsApp", title = "Alice", body = "see you"))

        assertEquals(1, h.history.recorded.size)
        assertEquals(1, classifier.callCount)
        assertEquals(1, h.sounder.callCount)
    }

    @Test
    fun differentContentSameApp_bothProcessed() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(), classifier)

        h.pipeline.processExtracted(extracted(key = "k1", body = "first"))
        h.pipeline.processExtracted(extracted(key = "k2", body = "second"))

        assertEquals(2, h.history.recorded.size)
        assertEquals(2, classifier.callCount)
        assertEquals(2, h.sounder.callCount)
    }

    // --- Active when locked (in-use gate) ---

    @Test
    fun phoneInUse_withActiveWhenLocked_skipsStoodAside_noAi() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(
            settings(activeWhenLocked = true),
            classifier,
            lockState = FakeLockStateProvider(inUse = true),
        )
        h.pipeline.processExtracted(extracted())

        assertEquals(Decision.SKIPPED.name, h.history.last?.decision)
        assertEquals(DecisionReasonCode.SKIPPED_PHONE_IN_USE.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
        assertFalse(h.history.last!!.aiCalled)
        assertEquals(0, classifier.callCount)
    }

    @Test
    fun notInUse_withActiveWhenLocked_proceedsToAi() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(
            settings(activeWhenLocked = true),
            classifier,
            lockState = FakeLockStateProvider(inUse = false),
        )
        h.pipeline.processExtracted(extracted())

        assertEquals(Decision.ALERT.name, h.history.last?.decision)
        assertEquals(1, classifier.callCount)
    }

    @Test
    fun phoneInUse_butActiveWhenLockedOff_proceedsToAi() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(
            settings(activeWhenLocked = false),
            classifier,
            lockState = FakeLockStateProvider(inUse = true),
        )
        h.pipeline.processExtracted(extracted())

        assertEquals(Decision.ALERT.name, h.history.last?.decision)
        assertEquals(1, classifier.callCount)
    }

    @Test
    fun lockState_isReReadPerNotification() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val lock = FakeLockStateProvider(inUse = true)
        val h = Harness(settings(activeWhenLocked = true), classifier, lockState = lock)

        // In use → stood aside. (Distinct bodies so content dedupe doesn't collapse the two.)
        h.pipeline.processExtracted(extracted(key = "k1", body = "alpha"))
        assertEquals(DecisionReasonCode.SKIPPED_PHONE_IN_USE.name, h.history.last?.reasonCode)
        assertEquals(0, classifier.callCount)

        // Flip live → next notification reaches the AI (no cached isInUse()).
        lock.inUse = false
        h.pipeline.processExtracted(extracted(key = "k2", body = "beta"))
        assertEquals(Decision.ALERT.name, h.history.last?.decision)
        assertEquals(1, classifier.callCount)
    }

    // --- Always alert ---

    @Test
    fun alwaysAlertApp_soundsWithoutCallingClassifier() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(always = setOf("com.example.app")), classifier)
        h.pipeline.processExtracted(extracted(pkg = "com.example.app"))

        assertEquals(Decision.ALERT.name, h.history.last?.decision)
        assertEquals(DecisionReasonCode.ALERT_ALWAYS.name, h.history.last?.reasonCode)
        assertFalse(h.history.last!!.aiCalled)
        assertTrue(h.history.last!!.wasAlerted)
        assertEquals(1, h.sounder.callCount)
        assertEquals(0, classifier.callCount) // AI is never consulted
    }

    @Test
    fun alwaysAlert_backstopTripped_recordsRateLimit_notSounded() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(always = setOf("com.example.app")), classifier)
        // Consume every global alert slot first.
        repeat(DedupeRateLimiter.MAX_ALERTS_PER_WINDOW) { assertTrue(h.dedupe.tryConsumeGlobalAlertSlot()) }

        h.pipeline.processExtracted(extracted(pkg = "com.example.app"))

        assertEquals(Decision.SKIPPED.name, h.history.last?.decision)
        assertEquals(DecisionReasonCode.SKIPPED_RATE_LIMIT.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
        assertEquals(0, classifier.callCount)
    }

    @Test
    fun alwaysAlert_beatsEligibility() = runTest {
        // App is NOT eligible (SELECTED_APPS + empty selection) but IS in always-alert.
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(
            settings(
                eligibilityMode = EligibilityMode.SELECTED_APPS,
                selected = emptySet(),
                always = setOf("com.example.app"),
            ),
            classifier,
        )
        h.pipeline.processExtracted(extracted(pkg = "com.example.app"))

        assertEquals(Decision.ALERT.name, h.history.last?.decision)
        assertEquals(DecisionReasonCode.ALERT_ALWAYS.name, h.history.last?.reasonCode)
        assertTrue(h.history.last!!.wasAlerted)
        assertEquals(0, classifier.callCount)
    }

    @Test
    fun protectedApp_inAlwaysAlert_stillProtected() = runTest {
        // Protected source is checked first, so it wins over always-alert.
        val classifier = ProgrammableClassifier(alert(0.95))
        val pkg = "com.google.android.dialer"
        val h = Harness(settings(always = setOf(pkg)), classifier)
        h.pipeline.processExtracted(extracted(pkg = pkg))

        assertEquals(DecisionReasonCode.SKIPPED_PROTECTED_SOURCE.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
        assertEquals(0, classifier.callCount)
    }

    @Test
    fun aiAlert_passesConfiguredSoundUriToSounder() = runTest {
        val uri = "content://media/internal/audio/media/42"
        val h = Harness(settings(alertSound = uri), ProgrammableClassifier(alert(0.95)))
        h.pipeline.processExtracted(extracted())

        assertEquals(1, h.sounder.callCount)
        assertEquals(uri, h.sounder.lastSoundUri)
    }

    @Test
    fun alwaysAlert_passesConfiguredSoundUriToSounder() = runTest {
        val uri = "content://media/internal/audio/media/7"
        val h = Harness(settings(always = setOf("com.example.app"), alertSound = uri), ProgrammableClassifier(alert(0.95)))
        h.pipeline.processExtracted(extracted(pkg = "com.example.app"))

        assertEquals(1, h.sounder.callCount)
        assertEquals(uri, h.sounder.lastSoundUri)
    }

    @Test
    fun nonAlwaysAlertApp_stillFlowsThroughAi() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(always = setOf("com.other.app")), classifier)
        h.pipeline.processExtracted(extracted(pkg = "com.example.app"))

        assertEquals(Decision.ALERT.name, h.history.last?.decision)
        assertEquals(DecisionReasonCode.ALERT_WORK_INCIDENT.name, h.history.last?.reasonCode) // from the classifier
        assertTrue(h.history.last!!.aiCalled)
        assertEquals(1, classifier.callCount)
    }
}

// --- helpers ---

private fun settings(
    smartQuiet: Boolean = true,
    deadSilent: Boolean = false,
    vibrate: Boolean = true,
    eligibilityMode: EligibilityMode = EligibilityMode.ALL_APPS_EXCEPT_SELECTED,
    selected: Set<String> = emptySet(),
    always: Set<String> = emptySet(),
    alertSound: String? = null,
    activeWhenLocked: Boolean = true,
): SettingsRepository = FakeSettingsRepository(
    AppSettings(
        smartQuietModeEnabled = smartQuiet,
        deadSilent = deadSilent,
        activeWhenLocked = activeWhenLocked,
        vibrateForCriticalAlerts = vibrate,
        eligibilityMode = eligibilityMode,
        selectedPackages = selected,
        alwaysAlertPackages = always,
        alertSoundUri = alertSound,
    ),
)

private fun alert(confidence: Double): ClassificationResult = ClassificationResult(
    decision = Decision.ALERT,
    confidence = confidence,
    reasonCode = DecisionReasonCode.ALERT_WORK_INCIDENT,
    userVisibleReason = "Outage reported.",
    modelName = "test",
    latencyMs = 1L,
)

private fun extracted(
    key: String = "key-1",
    pkg: String = "com.example.app",
    appLabel: String = "Example",
    title: String? = "Heads up",
    body: String? = "Production deployment failed, action needed",
    category: String? = null,
    isPersistent: Boolean = false,
    isGroupSummary: Boolean = false,
): ExtractedNotification = ExtractedNotification(
    notificationKey = key,
    packageName = pkg,
    appLabel = appLabel,
    postedAt = Instant.EPOCH,
    title = title,
    body = body,
    category = category,
    isPersistent = isPersistent,
    isGroupSummary = isGroupSummary,
    contentIntent = null,
)
