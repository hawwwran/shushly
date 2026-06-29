package com.hawwwran.shushly.service.listener

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedupe and rate limits (spec §7.5). Three independent limits:
 *  - content-hash dedupe (60 min): drop an exact-duplicate notification (same app + text) outright,
 *    before it reaches history or the AI — many apps re-post or rapidly update the same notification;
 *  - per-notification-key AI dedupe (30s): don't re-classify the same notification key rapidly (this
 *    still fires for same-key updates whose text *changed*, which content dedupe lets through);
 *  - a global anti-storm backstop: at most [MAX_ALERTS_PER_WINDOW] audible alerts per
 *    [WINDOW_MS] across all packages. This is a misfiring-app backstop, not a normal-use limit.
 * There is intentionally no per-package audible cooldown: important notifications sound immediately.
 */
@Singleton
class DedupeRateLimiter @Inject constructor() {

    private val lastAiCallByKey = ConcurrentHashMap<String, Long>()

    // contentHash -> first-seen time, within the current dedupe window. Guarded by `this`; stale
    // entries are evicted on each call so it stays bounded by the distinct content seen recently.
    private val firstSeenContentMs = HashMap<String, Long>()

    // Timestamps of recent audible alerts (any package). Guarded by `this`.
    private val recentAlertTimestamps = ArrayDeque<Long>()

    /**
     * True (and records the time) the first time this content signature is seen within
     * [CONTENT_DEDUPE_WINDOW_MS]; false for an exact repeat still inside the window. Lets an app's
     * re-posts / duplicate notifications be reported once per window instead of flooding.
     */
    @Synchronized
    fun isFreshContent(contentHash: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val cutoff = nowMs - CONTENT_DEDUPE_WINDOW_MS
        val it = firstSeenContentMs.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value <= cutoff) it.remove()
        }
        // After eviction, presence means "seen within the window" -> a duplicate to drop.
        if (firstSeenContentMs.containsKey(contentHash)) return false
        firstSeenContentMs[contentHash] = nowMs
        return true
    }

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
        const val CONTENT_DEDUPE_WINDOW_MS = 60L * 60 * 1000 // 60 minutes
    }
}
