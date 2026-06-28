package com.hawwwran.shushly.service.listener

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.hawwwran.shushly.core.model.ExtractedNotification
import java.time.Instant
import javax.inject.Inject

/** Extracts and normalizes notification text (spec §7.3). Tolerates redacted/empty text. */
class NotificationContentExtractor @Inject constructor() {

    fun extract(sbn: StatusBarNotification, appLabel: String): ExtractedNotification? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null

        val title = normalize(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(), MAX_TITLE)
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val body = normalize(bigText ?: text ?: subText, MAX_BODY)

        return ExtractedNotification(
            notificationKey = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            postedAt = Instant.ofEpochMilli(sbn.postTime),
            title = title,
            body = body,
            category = n.category,
            // Non-clearable covers both FLAG_ONGOING_EVENT and FLAG_NO_CLEAR — the full set of
            // "static" notifications the user never needs alerting about.
            isPersistent = !sbn.isClearable(),
            isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            contentIntent = n.contentIntent,
        )
    }

    /** True when there is meaningful, non-generic text to classify. */
    fun hasUsableText(notification: ExtractedNotification): Boolean {
        val text = notification.combinedText
        if (text.length < 2) return false
        return GENERIC_SUMMARIES.none { text.equals(it, ignoreCase = true) }
    }

    private fun normalize(raw: String?, max: Int): String? {
        if (raw == null) return null
        val cleaned = raw
            .replace(CONTROL_CHARS, " ")
            .replace(WHITESPACE, " ")
            .trim()
        if (cleaned.isBlank()) return null
        return if (cleaned.length > max) cleaned.substring(0, max) else cleaned
    }

    private companion object {
        const val MAX_TITLE = 256
        const val MAX_BODY = 1500
        val CONTROL_CHARS = Regex("[\\p{Cntrl}\\u200B-\\u200D\\uFEFF]")
        val WHITESPACE = Regex("\\s+")
        val GENERIC_SUMMARIES = listOf(
            "new message", "new messages", "new notification", "new notifications",
        )
    }
}
