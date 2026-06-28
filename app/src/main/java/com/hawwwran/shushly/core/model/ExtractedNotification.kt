package com.hawwwran.shushly.core.model

import android.app.PendingIntent
import java.time.Instant

/**
 * In-memory representation of a notification under consideration. The [contentIntent]
 * is kept only in memory long enough to build an actionable re-alert; it is never persisted.
 */
data class ExtractedNotification(
    val notificationKey: String,
    val packageName: String,
    val appLabel: String,
    val postedAt: Instant,
    val title: String?,
    val body: String?,
    val category: String?,
    /** Ongoing or non-clearable: a continuously shown "static" notification, not a discrete event. */
    val isPersistent: Boolean,
    val isGroupSummary: Boolean,
    val contentIntent: PendingIntent?,
) {
    /** Title + body, used for classification and dedupe hashing. */
    val combinedText: String
        get() = listOfNotNull(title, body).joinToString(separator = " ").trim()
}
