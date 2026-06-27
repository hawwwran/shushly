package com.hawwwran.shushly.service.quietmode

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure desired-zen-active rule: master && !(activeWhenLocked && inUse). */
class QuietModeLogicTest {

    private fun desired(master: Boolean, activeWhenLocked: Boolean, inUse: Boolean) =
        desiredZenActive(master, activeWhenLocked, inUse)

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
}
