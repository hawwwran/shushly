package com.hawwwran.shushly.feature.history

import com.hawwwran.shushly.core.data.db.DecisionHistoryEntity
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import com.hawwwran.shushly.core.model.EligibilityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringTest {

    private val pkg = "com.example.app"

    private fun entity(
        decision: Decision,
        reason: DecisionReasonCode,
        aiCalled: Boolean,
        wasAlerted: Boolean = false,
    ) = DecisionHistoryEntity(
        createdAtMs = 0L,
        packageName = pkg,
        appLabel = "Example",
        notificationKeyHash = "h",
        decision = decision.name,
        reasonCode = reason.name,
        userVisibleReason = null,
        aiCalled = aiCalled,
        wasAlerted = wasAlerted,
    )

    private fun settings(
        mode: EligibilityMode = EligibilityMode.ALL_APPS_EXCEPT_SELECTED,
        selected: Set<String> = emptySet(),
        always: Set<String> = emptySet(),
    ) = AppSettings(eligibilityMode = mode, selectedPackages = selected, alwaysAlertPackages = always)

    // --- AI judged: silent -> "should have alerted" ---

    @Test
    fun aiSilenced_offersShouldAlert_andAlwaysAlertConfig() {
        val s = steeringFor(
            entity(Decision.SILENT, DecisionReasonCode.SILENT_ROUTINE, aiCalled = true),
            settings(),
            hasCachedContent = true,
            hasExistingLearning = false,
            aiUsable = true,
        )
        assertEquals(SmartCorrection.SHOULD_ALERT, s.smart)
        assertTrue(s.showSmart)
        assertTrue(ConfigAction.ADD_ALWAYS_ALERT in s.configActions)
    }

    @Test
    fun aiAlerted_offersShouldSilent_andSilenceConfig() {
        val s = steeringFor(
            entity(Decision.ALERT, DecisionReasonCode.ALERT_WORK_INCIDENT, aiCalled = true, wasAlerted = true),
            settings(),
            hasCachedContent = true,
            hasExistingLearning = false,
            aiUsable = true,
        )
        assertEquals(SmartCorrection.SHOULD_SILENT, s.smart)
        assertTrue(s.showSmart)
        assertTrue(ConfigAction.SILENCE_APP in s.configActions)
    }

    @Test
    fun aiUnavailableFailSafe_offersShouldSilent() {
        val s = steeringFor(
            entity(Decision.ERROR, DecisionReasonCode.ERROR_AI_UNAVAILABLE, aiCalled = true, wasAlerted = true),
            settings(),
            hasCachedContent = true,
            hasExistingLearning = false,
            aiUsable = true,
        )
        assertEquals(SmartCorrection.SHOULD_SILENT, s.smart)
    }

    // --- Smart-button gating (content + AI) ---

    @Test
    fun smartHidden_whenContentExpired_butConfigStays() {
        val s = steeringFor(
            entity(Decision.ALERT, DecisionReasonCode.ALERT_WORK_INCIDENT, aiCalled = true, wasAlerted = true),
            settings(),
            hasCachedContent = false,
            hasExistingLearning = false,
            aiUsable = true,
        )
        assertEquals(SmartCorrection.SHOULD_SILENT, s.smart)
        assertFalse(s.showSmart) // no content to summarise
        assertTrue(ConfigAction.SILENCE_APP in s.configActions) // config doesn't need content
    }

    @Test
    fun smartShown_whenContentExpired_butAlreadyCorrected() {
        val s = steeringFor(
            entity(Decision.SILENT, DecisionReasonCode.SILENT_ROUTINE, aiCalled = true),
            settings(),
            hasCachedContent = false,
            hasExistingLearning = true,
            aiUsable = true,
        )
        assertTrue(s.showSmart) // already-saved correction stays visible/undoable
    }

    @Test
    fun smartHidden_whenAiNotUsable() {
        val s = steeringFor(
            entity(Decision.SILENT, DecisionReasonCode.SILENT_ROUTINE, aiCalled = true),
            settings(),
            hasCachedContent = true,
            hasExistingLearning = false,
            aiUsable = false,
        )
        assertFalse(s.showSmart)
        assertTrue(ConfigAction.ADD_ALWAYS_ALERT in s.configActions)
    }

    // --- Always-alert bypass ---

    @Test
    fun alwaysAlert_offersRemoveOnly_noSmart() {
        val s = steeringFor(
            entity(Decision.ALERT, DecisionReasonCode.ALERT_ALWAYS, aiCalled = false, wasAlerted = true),
            settings(always = setOf(pkg)),
            hasCachedContent = false,
            hasExistingLearning = false,
            aiUsable = true,
        )
        assertNull(s.smart)
        assertEquals(listOf(ConfigAction.REMOVE_ALWAYS_ALERT), s.configActions)
    }

    // --- Not eligible: blacklist (default) vs whitelist ---

    @Test
    fun notEligible_blacklistMode_offersMakeEligibleAndAlways() {
        // Default blacklist: "not eligible" means the app IS in the exclusion set.
        val s = steeringFor(
            entity(Decision.SKIPPED, DecisionReasonCode.SKIPPED_NOT_ELIGIBLE, aiCalled = false),
            settings(mode = EligibilityMode.ALL_APPS_EXCEPT_SELECTED, selected = setOf(pkg)),
            hasCachedContent = false,
            hasExistingLearning = false,
            aiUsable = true,
        )
        assertNull(s.smart)
        assertTrue(ConfigAction.MAKE_ELIGIBLE in s.configActions)
        assertTrue(ConfigAction.ADD_ALWAYS_ALERT in s.configActions)
    }

    @Test
    fun notEligible_whitelistMode_offersMakeEligibleAndAlways() {
        // Whitelist: "not eligible" means the app is NOT in the selected set.
        val s = steeringFor(
            entity(Decision.SKIPPED, DecisionReasonCode.SKIPPED_NOT_ELIGIBLE, aiCalled = false),
            settings(mode = EligibilityMode.SELECTED_APPS, selected = emptySet()),
            hasCachedContent = false,
            hasExistingLearning = false,
            aiUsable = true,
        )
        assertTrue(ConfigAction.MAKE_ELIGIBLE in s.configActions)
        assertTrue(ConfigAction.ADD_ALWAYS_ALERT in s.configActions)
    }

    // --- Protected + structural skips: explanation only ---

    @Test
    fun protectedSource_explanationOnly() {
        val s = steeringFor(
            entity(Decision.SKIPPED, DecisionReasonCode.SKIPPED_PROTECTED_SOURCE, aiCalled = false),
            settings(),
            hasCachedContent = false,
            hasExistingLearning = false,
            aiUsable = true,
        )
        assertNull(s.smart)
        assertTrue(s.configActions.isEmpty())
        assertTrue(s.explanation != null)
    }

    @Test
    fun quietModeOff_explanationOnly() {
        val s = steeringFor(
            entity(Decision.SKIPPED, DecisionReasonCode.SKIPPED_QUIET_MODE_OFF, aiCalled = false),
            settings(),
            hasCachedContent = false,
            hasExistingLearning = false,
            aiUsable = true,
        )
        assertNull(s.smart)
        assertTrue(s.configActions.isEmpty())
        assertTrue(s.explanation != null)
    }

    // --- Always-silenced (history-row dimming) ---

    @Test
    fun alwaysSilenced_blacklistDefault_eligibleByDefault() {
        // Default blacklist with empty selection: every app is eligible, so nothing is dimmed.
        assertFalse(isAlwaysSilenced(pkg, settings()))
    }

    @Test
    fun alwaysSilenced_blacklist_excludedApp_isSilenced() {
        assertTrue(isAlwaysSilenced(pkg, settings(selected = setOf(pkg))))
    }

    @Test
    fun alwaysSilenced_alwaysAlertBeatsExclusion() {
        // On the always-alert list it always sounds, even when also in the exclusion set.
        assertFalse(isAlwaysSilenced(pkg, settings(selected = setOf(pkg), always = setOf(pkg))))
    }

    @Test
    fun alwaysSilenced_whitelist_unselectedApp_isSilenced() {
        assertTrue(isAlwaysSilenced(pkg, settings(mode = EligibilityMode.SELECTED_APPS, selected = emptySet())))
    }

    @Test
    fun alwaysSilenced_whitelist_selectedApp_isNotSilenced() {
        assertFalse(isAlwaysSilenced(pkg, settings(mode = EligibilityMode.SELECTED_APPS, selected = setOf(pkg))))
    }

    @Test
    fun alwaysSilenced_whitelist_alwaysAlertUnselected_isNotSilenced() {
        assertFalse(isAlwaysSilenced(pkg, settings(mode = EligibilityMode.SELECTED_APPS, always = setOf(pkg))))
    }

    // --- Labels ---

    @Test
    fun labels_areIntentBased() {
        assertEquals("This should have alerted", SmartCorrection.SHOULD_ALERT.label())
        assertEquals("Always alert for News", ConfigAction.ADD_ALWAYS_ALERT.label("News"))
        assertEquals("Let the AI decide for News", ConfigAction.MAKE_ELIGIBLE.label("News"))
    }
}
