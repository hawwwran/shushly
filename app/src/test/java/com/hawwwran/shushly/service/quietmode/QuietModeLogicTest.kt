package com.hawwwran.shushly.service.quietmode

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure desired-zen-active rule: deadSilent || (master && !(activeWhenLocked && inUse)). */
class QuietModeLogicTest {

    private fun desired(master: Boolean, activeWhenLocked: Boolean, inUse: Boolean, deadSilent: Boolean = false) =
        desiredZenActive(master, activeWhenLocked, inUse, deadSilent)

    @Test
    fun masterOff_alwaysOff() {
        assertEquals(false, desired(master = false, activeWhenLocked = false, inUse = false))
        assertEquals(false, desired(master = false, activeWhenLocked = false, inUse = true))
        assertEquals(false, desired(master = false, activeWhenLocked = true, inUse = false))
        assertEquals(false, desired(master = false, activeWhenLocked = true, inUse = true))
    }

    @Test
    fun masterOn_activeWhenLockedOff_onAlways() {
        assertEquals(true, desired(master = true, activeWhenLocked = false, inUse = false))
        assertEquals(true, desired(master = true, activeWhenLocked = false, inUse = true))
    }

    @Test
    fun masterOn_activeWhenLockedOn_onlyWhenNotInUse() {
        assertEquals(true, desired(master = true, activeWhenLocked = true, inUse = false))
        assertEquals(false, desired(master = true, activeWhenLocked = true, inUse = true))
    }

    @Test
    fun deadSilent_forcesOn_evenWithMasterOff() {
        // Dead silent is a total-silence override: the rule must be active even when the master is off
        // and regardless of the in-use stand-aside.
        assertEquals(true, desired(master = false, activeWhenLocked = false, inUse = false, deadSilent = true))
        assertEquals(true, desired(master = false, activeWhenLocked = true, inUse = true, deadSilent = true))
        assertEquals(true, desired(master = true, activeWhenLocked = true, inUse = true, deadSilent = true))
    }
}
