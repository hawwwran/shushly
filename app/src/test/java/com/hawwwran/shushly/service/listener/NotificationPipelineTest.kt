/*
 * Pipeline-level decision tests (spec §16.1): threshold, fail-to-silent, simulation, the anti-storm
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
    ) {
        val sounder = RecordingSounder()
        val history = RecordingHistoryRepository()
        val pipeline = NotificationPipeline(
            settings = settings,
            extractor = NotificationContentExtractor(),
            eligibility = NotificationEligibilityEvaluator(),
            dedupe = dedupe,
            classifier = classifier,
            sounder = sounder,
            seenApps = FakeSeenAppsRepository(),
            history = history,
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

    @Test
    fun alertBelowThreshold_recordsSilent_notSounded() = runTest {
        val h = Harness(settings(), ProgrammableClassifier(alert(0.79)))
        h.pipeline.processExtracted(extracted())

        assertEquals(Decision.SILENT.name, h.history.last?.decision)
        assertEquals(0, h.sounder.callCount)
        assertFalse(h.history.last!!.wasAlerted)
        assertTrue(h.history.last!!.aiCalled)
    }

    // --- Fail-to-silent (§3.4) ---

    @Test
    fun classifierThrows_recordsError_notSounded() = runTest {
        val h = Harness(settings(), ProgrammableClassifier(error = RuntimeException("relay down")))
        h.pipeline.processExtracted(extracted())

        assertEquals(Decision.ERROR.name, h.history.last?.decision)
        assertEquals(DecisionReasonCode.ERROR_AI_UNAVAILABLE.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
        assertTrue(h.history.last!!.aiCalled)
    }

    // --- Simulation (§14.2) ---

    @Test
    fun simulationMode_highConfidenceAlert_recordsWouldAlert_notSounded() = runTest {
        val h = Harness(settings(simulation = true), ProgrammableClassifier(alert(0.95)))
        h.pipeline.processExtracted(extracted())

        assertEquals(Decision.WOULD_ALERT.name, h.history.last?.decision)
        assertEquals(0, h.sounder.callCount)
        assertTrue(h.history.last!!.aiCalled)
        assertFalse(h.history.last!!.wasAlerted)
    }

    // --- Anti-storm backstop (§7.5) ---

    @Test
    fun antiStormBackstop_capsAudibleAlertsAtWindowMax() = runTest {
        val cap = DedupeRateLimiter.MAX_ALERTS_PER_WINDOW
        val h = Harness(settings(), ProgrammableClassifier(alert(0.95)))

        repeat(cap + 1) { i -> h.pipeline.processExtracted(extracted(key = "key-$i")) }

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

        h.pipeline.processExtracted(extracted(key = "dup"))
        h.pipeline.processExtracted(extracted(key = "dup"))

        val records = h.history.recorded
        assertEquals(2, records.size)
        assertEquals(Decision.ALERT.name, records[0].decision)
        assertTrue(records[0].wasAlerted)
        assertEquals(Decision.SKIPPED.name, records[1].decision)
        assertEquals(DecisionReasonCode.SKIPPED_DUPLICATE.name, records[1].reasonCode)
        assertFalse(records[1].aiCalled)
        assertEquals(1, h.sounder.callCount) // only the first sounded
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
    fun alwaysAlert_simulation_recordsWouldAlert_notSounded() = runTest {
        val classifier = ProgrammableClassifier(alert(0.95))
        val h = Harness(settings(simulation = true, always = setOf("com.example.app")), classifier)
        h.pipeline.processExtracted(extracted(pkg = "com.example.app"))

        assertEquals(Decision.WOULD_ALERT.name, h.history.last?.decision)
        assertEquals(DecisionReasonCode.ALERT_ALWAYS.name, h.history.last?.reasonCode)
        assertEquals(0, h.sounder.callCount)
        assertEquals(0, classifier.callCount)
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
    simulation: Boolean = false,
    vibrate: Boolean = true,
    eligibilityMode: EligibilityMode = EligibilityMode.ALL_APPS_EXCEPT_SELECTED,
    selected: Set<String> = emptySet(),
    always: Set<String> = emptySet(),
    alertSound: String? = null,
): SettingsRepository = FakeSettingsRepository(
    AppSettings(
        smartQuietModeEnabled = smartQuiet,
        simulationModeEnabled = simulation,
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
    title: String? = "Heads up",
    body: String? = "Production deployment failed, action needed",
    category: String? = null,
    isOngoing: Boolean = false,
    isGroupSummary: Boolean = false,
): ExtractedNotification = ExtractedNotification(
    notificationKey = key,
    packageName = pkg,
    appLabel = "Example",
    postedAt = Instant.EPOCH,
    title = title,
    body = body,
    category = category,
    isOngoing = isOngoing,
    isGroupSummary = isGroupSummary,
    contentIntent = null,
)
