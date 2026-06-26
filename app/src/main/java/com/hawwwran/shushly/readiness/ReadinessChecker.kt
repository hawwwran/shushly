package com.hawwwran.shushly.readiness

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.hawwwran.shushly.service.alerting.NotificationChannels
import com.hawwwran.shushly.service.listener.ShushlyNotificationListenerService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Live checks behind the readiness checklist (spec §2.4). */
@Singleton
class ReadinessChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val nm: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun listenerEnabled(): Boolean {
        val component = ComponentName(context, ShushlyNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return flat.split(":")
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == component }
    }

    fun policyAccessGranted(): Boolean =
        runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false)

    fun postNotificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun criticalChannelCanAlert(): Boolean = NotificationChannels.criticalChannelCanAlert(context)

    fun minimumRequirementsMet(): Boolean =
        listenerEnabled() && policyAccessGranted() && postNotificationsGranted() && criticalChannelCanAlert()
}
