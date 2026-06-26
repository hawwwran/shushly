package com.hawwwran.shushly.service.listener

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedupe and rate limits (spec §7.5). Phase-0 subset: per-notification-key AI cooldown and
 * per-package audible-alert cooldown. The full conversation/content/daily limits land in Phase 1.
 */
@Singleton
class DedupeRateLimiter @Inject constructor() {

    private val lastAiCallByKey = ConcurrentHashMap<String, Long>()
    private val lastAlertByPackage = ConcurrentHashMap<String, Long>()

    /** True (and records the time) if no AI call for this key within the cooldown. */
    fun canCallAi(notificationKey: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        passed(lastAiCallByKey, notificationKey, AI_COOLDOWN_MS, nowMs)

    /** True (and records the time) if no audible alert for this package within the cooldown. */
    fun canAlert(packageName: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        passed(lastAlertByPackage, packageName, ALERT_COOLDOWN_MS, nowMs)

    private fun passed(
        map: ConcurrentHashMap<String, Long>,
        key: String,
        cooldownMs: Long,
        nowMs: Long,
    ): Boolean {
        val last = map[key]
        return if (last == null || nowMs - last >= cooldownMs) {
            map[key] = nowMs
            true
        } else {
            false
        }
    }

    companion object {
        const val AI_COOLDOWN_MS = 30_000L
        const val ALERT_COOLDOWN_MS = 60_000L
    }
}
