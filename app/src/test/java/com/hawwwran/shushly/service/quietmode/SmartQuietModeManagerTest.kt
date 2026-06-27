package com.hawwwran.shushly.service.quietmode

import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.DecisionReasonCode
import com.hawwwran.shushly.service.ai.FakeSettingsRepository
import com.hawwwran.shushly.service.listener.FakeLockStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records enable/disable and the resulting enabled state; can force enable() → Unavailable. */
private class FakeQuietModeController : QuietModeController {
    var enableCount = 0
        private set
    var disableCount = 0
        private set
    var enabled = false
        private set
    var enableResult: QuietModeResult = QuietModeResult.Success
    private val stateFlow = MutableStateFlow(QuietModeState())

    override suspend fun enable(): QuietModeResult {
        enableCount++
        val result = enableResult
        if (result is QuietModeResult.Success) enabled = true
        return result
    }

    override suspend fun disable(): QuietModeResult {
        disableCount++
        enabled = false
        return QuietModeResult.Success
    }

    override fun observeState(): StateFlow<QuietModeState> = stateFlow.asStateFlow()
    override fun refresh() {}
}

class SmartQuietModeManagerTest {

    private fun manager(
        settings: FakeSettingsRepository,
        controller: FakeQuietModeController,
        lock: FakeLockStateProvider,
    ) = SmartQuietModeManager(controller, settings, lock)

    @Test
    fun setEnabledFalse_disablesAndPersistsOff() = runTest {
        val settings = FakeSettingsRepository(AppSettings(smartQuietModeEnabled = true, activeWhenLocked = true))
        val controller = FakeQuietModeController()
        val result = manager(settings, controller, FakeLockStateProvider(inUse = false)).setEnabled(false)

        assertFalse(result)
        assertFalse(settings.current.smartQuietModeEnabled)
        assertFalse(controller.enabled)
        assertTrue(controller.disableCount >= 1)
    }

    @Test
    fun setEnabledTrue_notInUse_endsEnabled() = runTest {
        val settings = FakeSettingsRepository(AppSettings(smartQuietModeEnabled = false, activeWhenLocked = true))
        val controller = FakeQuietModeController()
        val result = manager(settings, controller, FakeLockStateProvider(inUse = false)).setEnabled(true)

        assertTrue(result)
        assertTrue(settings.current.smartQuietModeEnabled)
        assertTrue(controller.enabled)
    }

    /** Headline contract: enabling while in use + activeWhenLocked persists master ON but the rule ends OFF. */
    @Test
    fun setEnabledTrue_inUse_persistsMasterTrueButRuleEndsOff() = runTest {
        val settings = FakeSettingsRepository(AppSettings(smartQuietModeEnabled = false, activeWhenLocked = true))
        val controller = FakeQuietModeController()
        val result = manager(settings, controller, FakeLockStateProvider(inUse = true)).setEnabled(true)

        assertTrue(result) // returns the master intent
        assertTrue(settings.current.smartQuietModeEnabled) // master persisted true
        assertFalse(controller.enabled) // but the zen rule stood aside
        assertTrue(controller.enableCount >= 1) // enable() was attempted
        assertTrue(controller.disableCount >= 1) // reconcile() turned it back off
    }

    @Test
    fun setEnabledTrue_unavailable_returnsFalse_masterStaysOff() = runTest {
        val settings = FakeSettingsRepository(AppSettings(smartQuietModeEnabled = false))
        val controller = FakeQuietModeController().apply {
            enableResult = QuietModeResult.Unavailable(DecisionReasonCode.ERROR_PERMISSION_MISSING)
        }
        val result = manager(settings, controller, FakeLockStateProvider(inUse = false)).setEnabled(true)

        assertFalse(result)
        assertFalse(settings.current.smartQuietModeEnabled)
        assertFalse(controller.enabled)
    }

    @Test
    fun setActiveWhenLocked_togglesRuleWhileInUse() = runTest {
        // Master on, phone in use.
        val settings = FakeSettingsRepository(AppSettings(smartQuietModeEnabled = true, activeWhenLocked = true))
        val controller = FakeQuietModeController()
        val m = manager(settings, controller, FakeLockStateProvider(inUse = true))

        m.setActiveWhenLocked(true)
        assertTrue(settings.current.activeWhenLocked)
        assertFalse(controller.enabled) // in use + activeWhenLocked → stands aside

        m.setActiveWhenLocked(false)
        assertFalse(settings.current.activeWhenLocked)
        assertTrue(controller.enabled) // activeWhenLocked off → rule on regardless of use
    }

    @Test
    fun reconcile_combinations() = runTest {
        // master off → disable
        FakeQuietModeController().let { c ->
            manager(FakeSettingsRepository(AppSettings(smartQuietModeEnabled = false)), c, FakeLockStateProvider(false)).reconcile()
            assertFalse(c.enabled)
        }
        // master on, activeWhenLocked off, in use → enable (original behavior)
        FakeQuietModeController().let { c ->
            manager(FakeSettingsRepository(AppSettings(smartQuietModeEnabled = true, activeWhenLocked = false)), c, FakeLockStateProvider(true)).reconcile()
            assertTrue(c.enabled)
        }
        // master on, activeWhenLocked on, in use → disable (stands aside)
        FakeQuietModeController().let { c ->
            manager(FakeSettingsRepository(AppSettings(smartQuietModeEnabled = true, activeWhenLocked = true)), c, FakeLockStateProvider(true)).reconcile()
            assertFalse(c.enabled)
        }
        // master on, activeWhenLocked on, NOT in use → enable
        FakeQuietModeController().let { c ->
            manager(FakeSettingsRepository(AppSettings(smartQuietModeEnabled = true, activeWhenLocked = true)), c, FakeLockStateProvider(false)).reconcile()
            assertTrue(c.enabled)
        }
    }
}
