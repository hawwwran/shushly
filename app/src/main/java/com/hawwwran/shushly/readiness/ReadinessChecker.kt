package com.hawwwran.shushly.readiness

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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

    private val audioManager: AudioManager =
        context.getSystemService(AudioManager::class.java)

    private val powerManager: PowerManager =
        context.getSystemService(PowerManager::class.java)

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

    /**
     * The alert tone rides the alarm stream (the alarm lane is the only one Smart Quiet Mode leaves
     * open). If alarm volume is 0, Shushly is inaudible even when everything else is configured.
     * Advisory only, not a hard minimum — volume is transient and user-controllable.
     */
    fun alarmAudible(): Boolean =
        runCatching { audioManager.getStreamVolume(AudioManager.STREAM_ALARM) > 0 }.getOrDefault(true)

    /**
     * Whether Shushly is exempt from battery optimization. Advisory: on aggressive OEMs (FunTouch)
     * Doze can kill the notification-listener binding, so an exemption keeps it alive. Not a hard
     * minimum — the app still works when foregrounded.
     */
    fun batteryOptimizationExempt(): Boolean =
        runCatching { powerManager.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)

    /** Sound-only alerting needs the listener bound and DND-policy access (for the zen rule). */
    fun minimumRequirementsMet(): Boolean =
        listenerEnabled() && policyAccessGranted()
}
