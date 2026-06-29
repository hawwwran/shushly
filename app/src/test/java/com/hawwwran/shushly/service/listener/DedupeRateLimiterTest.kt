package com.hawwwran.shushly.service.listener

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupeRateLimiterTest {

    // canCallAi — per-key 30s cooldown. Explicit nowMs; never the real clock.

    @Test
    fun canCallAi_firstCallForKey_true() {
        val limiter = DedupeRateLimiter()
        assertTrue(limiter.canCallAi("k", nowMs = 0))
    }

    @Test
    fun canCallAi_sameKeyWithinCooldown_false() {
        val limiter = DedupeRateLimiter()
        limiter.canCallAi("k", nowMs = 0)
        assertFalse(limiter.canCallAi("k", nowMs = 10_000))
    }

    @Test
    fun canCallAi_sameKeyAfterCooldown_true() {
        val limiter = DedupeRateLimiter()
        limiter.canCallAi("k", nowMs = 0)
        assertTrue(limiter.canCallAi("k", nowMs = DedupeRateLimiter.AI_COOLDOWN_MS))
    }

    @Test
    fun canCallAi_differentKey_isIndependent() {
        val limiter = DedupeRateLimiter()
        limiter.canCallAi("k", nowMs = 0)
        assertTrue(limiter.canCallAi("other", nowMs = 10_000))
    }

    // isFreshContent — content-hash dedupe over a 60 min window. Explicit nowMs; never the real clock.

    @Test
    fun isFreshContent_firstSighting_true() {
        val limiter = DedupeRateLimiter()
        assertTrue(limiter.isFreshContent("h", nowMs = 0))
    }

    @Test
    fun isFreshContent_sameHashWithinWindow_false() {
        val limiter = DedupeRateLimiter()
        limiter.isFreshContent("h", nowMs = 0)
        assertFalse(limiter.isFreshContent("h", nowMs = 10_000))
    }

    @Test
    fun isFreshContent_sameHashAfterWindow_true() {
        val limiter = DedupeRateLimiter()
        limiter.isFreshContent("h", nowMs = 0)
        assertTrue(limiter.isFreshContent("h", nowMs = DedupeRateLimiter.CONTENT_DEDUPE_WINDOW_MS))
    }

    @Test
    fun isFreshContent_differentHash_isIndependent() {
        val limiter = DedupeRateLimiter()
        limiter.isFreshContent("h", nowMs = 0)
        assertTrue(limiter.isFreshContent("other", nowMs = 10_000))
    }

    // isFirstInBurst — ~1s same-instant burst collapse. Explicit nowMs; never the real clock.

    @Test
    fun isFirstInBurst_firstSighting_true() {
        val limiter = DedupeRateLimiter()
        assertTrue(limiter.isFirstInBurst("h", nowMs = 0))
    }

    @Test
    fun isFirstInBurst_sameContentWithinWindow_false() {
        val limiter = DedupeRateLimiter()
        limiter.isFirstInBurst("h", nowMs = 0)
        assertFalse(limiter.isFirstInBurst("h", nowMs = 200))
    }

    @Test
    fun isFirstInBurst_sameContentAfterWindow_true() {
        val limiter = DedupeRateLimiter()
        limiter.isFirstInBurst("h", nowMs = 0)
        assertTrue(limiter.isFirstInBurst("h", nowMs = DedupeRateLimiter.BURST_DEDUPE_WINDOW_MS))
    }

    @Test
    fun isFirstInBurst_differentContent_isIndependent() {
        val limiter = DedupeRateLimiter()
        limiter.isFirstInBurst("h", nowMs = 0)
        assertTrue(limiter.isFirstInBurst("other", nowMs = 200))
    }

    @Test
    fun isFirstInBurst_andIsFreshContent_useSeparateWindows() {
        // A repeat 5 min later is past the ~1s burst window (fresh burst) but still inside the 60-min
        // content window (a duplicate there) — the two limits are independent and don't share state.
        val limiter = DedupeRateLimiter()
        val fiveMin = 5L * 60 * 1000
        assertTrue(limiter.isFreshContent("h", nowMs = 0))
        assertTrue(limiter.isFirstInBurst("h", nowMs = 0))
        assertTrue(limiter.isFirstInBurst("h", nowMs = fiveMin)) // past 1s burst window
        assertFalse(limiter.isFreshContent("h", nowMs = fiveMin)) // still inside 60-min window
    }

    // tryConsumeGlobalAlertSlot — global anti-storm backstop, cap per rolling window.

    @Test
    fun globalBackstop_allowsUpToCapThenBlocks() {
        val limiter = DedupeRateLimiter()
        repeat(DedupeRateLimiter.MAX_ALERTS_PER_WINDOW) {
            assertTrue(limiter.tryConsumeGlobalAlertSlot(nowMs = 1_000))
        }
        assertFalse(limiter.tryConsumeGlobalAlertSlot(nowMs = 1_000))
    }

    @Test
    fun globalBackstop_recoversOnceWindowElapses() {
        val limiter = DedupeRateLimiter()
        repeat(DedupeRateLimiter.MAX_ALERTS_PER_WINDOW) {
            limiter.tryConsumeGlobalAlertSlot(nowMs = 1_000)
        }
        assertFalse(limiter.tryConsumeGlobalAlertSlot(nowMs = 1_000))

        // Advance strictly past the window so the earlier timestamps fall out of it.
        val pastWindow = 1_000 + DedupeRateLimiter.WINDOW_MS + 1
        assertTrue(limiter.tryConsumeGlobalAlertSlot(nowMs = pastWindow))
    }
}
