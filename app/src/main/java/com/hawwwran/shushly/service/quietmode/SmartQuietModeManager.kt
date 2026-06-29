package com.hawwwran.shushly.service.quietmode

import com.hawwwran.shushly.core.data.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Desired active state of the app-owned zen rule (pure, unit-tested).
 *  - deadSilent on → zen on always (total-silence override; wins over everything below).
 *  - master off → zen off.
 *  - master on + activeWhenLocked off → zen on always (the original behavior).
 *  - master on + activeWhenLocked on → zen on only while NOT in use (locked/away).
 */
fun desiredZenActive(master: Boolean, activeWhenLocked: Boolean, inUse: Boolean, deadSilent: Boolean): Boolean =
    deadSilent || (master && !(activeWhenLocked && inUse))

/**
 * Single source of truth for toggling Smart Quiet Mode: it drives the app-owned zen rule via
 * [QuietModeController] and persists the user's intent via [SettingsRepository] together, so the
 * Home toggle and the Quick Settings tile can't drift apart.
 *
 * The zen rule's *active* state is reconciled against the live lock state ([LockStateProvider]) so
 * that, with "Active when locked" on, Shushly stands aside while the phone is in use — without ever
 * mutating the persisted master flag (the user's intent).
 */
@Singleton
class SmartQuietModeManager @Inject constructor(
    private val quietMode: QuietModeController,
    private val settings: SettingsRepository,
    private val lockState: LockStateProvider,
) {
    // Serializes reconcile() so overlapping triggers (e.g. SCREEN_ON then USER_PRESENT on unlock, each
    // on its own coroutine) can't interleave their enable()/disable() binder calls out of order.
    private val reconcileMutex = Mutex()
    /**
     * Enables or disables Smart Quiet Mode (the persisted master). Returns the master state it ended
     * up in: enabling can fail to [QuietModeResult.Unavailable] (e.g. no policy access), leaving it
     * off. After persisting, [reconcile] sets the zen rule's active state from the live lock state
     * (so enabling while in-use + activeWhenLocked correctly leaves the rule off).
     */
    suspend fun setEnabled(on: Boolean): Boolean = if (on) {
        when (quietMode.enable()) {
            is QuietModeResult.Success -> {
                settings.setSmartQuietMode(true)
                reconcile()
                true
            }
            is QuietModeResult.Unavailable -> {
                settings.setSmartQuietMode(false)
                false
            }
        }
    } else {
        settings.setSmartQuietMode(false)
        reconcile()
        false
    }

    /** Persist the "Active when locked" sub-option, then reconcile the zen rule against lock state. */
    suspend fun setActiveWhenLocked(on: Boolean) {
        settings.setActiveWhenLocked(on)
        reconcile()
    }

    /**
     * Persist Dead silent (a total-silence override) and reconcile immediately: enabling forces the zen
     * rule active with the stricter all-but-media policy; disabling reverts to the master/lock state and
     * the normal policy. Independent of the Smart Quiet Mode master — works even when it is off.
     */
    suspend fun setDeadSilent(on: Boolean) {
        settings.setDeadSilent(on)
        reconcile()
    }

    /**
     * Flip the zen rule to match [desiredZenActive] for the current settings + lock state. Never
     * touches the persisted master flag — that's the user's intent and stays put.
     */
    suspend fun reconcile() {
        // Read isInUse() inside the lock so the reconcile that acquires it last reads the settled
        // lock state and applies the correct final zen state.
        reconcileMutex.withLock {
            val s = settings.snapshot()
            if (desiredZenActive(s.smartQuietModeEnabled, s.activeWhenLocked, lockState.isInUse(), s.deadSilent)) {
                quietMode.enable()
            } else {
                quietMode.disable()
            }
        }
    }

    /** The persisted Smart Quiet Mode flag (the user's intent, kept in sync with the zen rule). */
    suspend fun isEnabled(): Boolean = settings.snapshot().smartQuietModeEnabled
}
