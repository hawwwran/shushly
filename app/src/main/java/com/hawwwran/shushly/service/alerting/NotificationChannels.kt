package com.hawwwran.shushly.service.alerting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Shushly's own channels (spec §4.3). The critical channel must be created the FIRST time
 * while DND-policy access is held, so its bypass-DND flag sticks (importance/bypass are
 * immutable from code after first creation). Create base channels eagerly; create the
 * critical channel only once policy access is granted.
 */
object NotificationChannels {
    const val CRITICAL = "critical_alerts"
    const val CRITICAL_NO_VIBRATE = "critical_alerts_quiet"
    const val STATUS = "service_status"
    const val SETUP = "setup_issues"

    fun ensureBaseChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(STATUS, "Shushly status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Operational status. Never audible."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(SETUP, "Setup issues", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Permission or configuration warnings."
            },
        )
    }

    /**
     * Creates the two critical channels (vibrating / non-vibrating) with DND bypass, but only
     * while policy access is held. Vibration is immutable after channel creation and per-notification
     * vibration is ignored on O+, so the "Vibrate for critical alerts" toggle selects the channel
     * rather than mutating one.
     */
    fun ensureCriticalChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        if (nm.getNotificationChannel(CRITICAL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CRITICAL, "Critical alerts (vibrate)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Audible, vibrating alert when Shushly judges a notification critical."
                    setBypassDnd(true)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }
        if (nm.getNotificationChannel(CRITICAL_NO_VIBRATE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CRITICAL_NO_VIBRATE, "Critical alerts (no vibrate)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Audible, non-vibrating alert when Shushly judges a notification critical."
                    setBypassDnd(true)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }
    }

    /** The critical channel to post on, honoring the user's vibration preference. */
    fun channelForVibrate(vibrate: Boolean): String = if (vibrate) CRITICAL else CRITICAL_NO_VIBRATE

    fun ensureAll(context: Context) {
        ensureBaseChannels(context)
        ensureCriticalChannel(context)
    }

    /** Runtime check for the readiness item "Critical alert channel can make sound" (spec §2.4). */
    fun criticalChannelCanAlert(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        val channel = nm.getNotificationChannel(CRITICAL) ?: return false
        return channel.importance >= NotificationManager.IMPORTANCE_DEFAULT && channel.canBypassDnd()
    }
}
