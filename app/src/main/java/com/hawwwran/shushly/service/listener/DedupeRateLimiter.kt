package com.hawwwran.shushly.service.listener

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedupe and rate limits (spec §7.5). Two independent limits:
 *  - per-notification-key AI dedupe (30s): don't re-classify the same notification rapidly;
 *  - a global anti-storm backstop: at most [MAX_ALERTS_PER_WINDOW] audible alerts per
 *    [WINDOW_MS] across all packages. This is a misfiring-app backstop, not a normal-use limit.
 * There is intentionally no per-package audible cooldown: important notifications sound immediately.
 */
@Singleton
class DedupeRateLimiter @Inject constructor() {

    private val lastAiCallByKey = ConcurrentHashMap<String, Long>()

    // Timestamps of recent audible alerts (any package). Guarded by `this`.
    private val recentAlertTimestamps = ArrayDeque<Long>()

    /** True (and records the time) if no AI call for this key within the cooldown. */
    fun canCallAi(notificationKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = lastAiCallByKey[notificationKey]
        return if (last == null || nowMs - last >= AI_COOLDOWN_MS) {
            lastAiCallByKey[notificationKey] = nowMs
            true
        } else {
            false
        }
    }

    /**
     * Global anti-storm gate: returns true and consumes a slot if under the cap within the rolling
     * window; returns false (alert should stay silent) if the cap is reached.
     */
    @Synchronized
    fun tryConsumeGlobalAlertSlot(nowMs: Long = System.currentTimeMillis()): Boolean {
        val cutoff = nowMs - WINDOW_MS
        while (recentAlertTimestamps.isNotEmpty() && recentAlertTimestamps.first() < cutoff) {
            recentAlertTimestamps.removeFirst()
        }
        return if (recentAlertTimestamps.size < MAX_ALERTS_PER_WINDOW) {
            recentAlertTimestamps.addLast(nowMs)
            true
        } else {
            false
        }
    }

    companion object {
        const val AI_COOLDOWN_MS = 30_000L
        const val MAX_ALERTS_PER_WINDOW = 10
        const val WINDOW_MS = 60_000L
    }
}
