package com.hawwwran.shushly.feature.common

import android.content.Context
import android.content.Intent
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
