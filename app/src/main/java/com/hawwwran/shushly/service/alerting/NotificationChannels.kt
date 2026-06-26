package com.hawwwran.shushly.service.alerting

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Shushly's own notification channels. These are for the service status / setup notifications, not
 * the important-notification alert: that is sound-only and posts no notification (see
 * [CriticalAlertSounder]).
 */
object NotificationChannels {
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
}
