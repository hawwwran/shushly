package com.hawwwran.shushly.feature.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.Color

/** Bits shared across the navigated screens. */

/** Green used for satisfied/alert states. */
internal val OkColor = Color(0xFF2E7D32)

internal fun Context.startSafely(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

internal fun Context.openAppNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    startSafely(intent)
}

/**
 * Opens the "ignore battery optimizations" request for this app, falling back to the app-details
 * screen if that intent isn't resolvable on the device.
 */
internal fun Context.openBatteryOptimizationSettings() {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:$packageName"))
    if (request.resolveActivity(packageManager) != null) {
        startSafely(request)
    } else {
        startSafely(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")),
        )
    }
}
