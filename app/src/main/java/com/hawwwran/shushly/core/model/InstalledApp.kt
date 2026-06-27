package com.hawwwran.shushly.core.model

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A launchable installed app shown in the app picker. [icon] is preloaded for display ([icon] is
 * null when it failed to load, or for an unavailable/uninstalled-but-selected entry).
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val isProtected: Boolean,
    val isAvailable: Boolean = true,
)
