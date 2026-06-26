package com.hawwwran.shushly.core.model

import android.app.PendingIntent

/** A re-alert Shushly is about to raise on its own critical-alerts channel. */
data class CriticalAlert(
    val sourcePackage: String,
    val sourceLabel: String,
    val titleLine: String,
    val reasonLine: String,
    val sourceContentIntent: PendingIntent?,
    val vibrate: Boolean,
)
