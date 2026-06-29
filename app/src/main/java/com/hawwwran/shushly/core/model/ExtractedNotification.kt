package com.hawwwran.shushly.core.model

import android.app.PendingIntent
import java.security.MessageDigest
import java.security.SecureRandom
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

    /**
     * Stable signature for content-level dedupe: app + normalized title + body. Two notifications with
     * the same signature are treated as the same event (an app re-posting/updating the same content).
     * The NUL separators keep field boundaries unambiguous so distinct fields can't collide by
     * concatenation. The title is run through [normalizedTitle] first.
     */
    val contentSignature: String
        get() = "$packageName\u0000${normalizedTitle()}\u0000${body.orEmpty()}"

    /**
     * Title with a leading app-name prefix removed. Some apps post the same message both with and
     * without their own name prefixed onto the title — observed: WhatsApp posts one notification titled
     * "WhatsApp: Alice" and another titled "Alice" for a single message. Stripping a leading "[appLabel]"
     * (plus any ": " / " - " separator) makes those collapse to one event for dedupe. The distinguishing
     * remainder is untouched, so distinct senders ("WhatsApp: Alice" vs "WhatsApp: Bob") stay distinct.
     */
    private fun normalizedTitle(): String {
        val t = title.orEmpty().trim()
        val label = appLabel.trim()
        return if (label.isNotEmpty() && t.startsWith(label, ignoreCase = true)) {
            t.substring(label.length).trimStart(' ', ':', '-', '|').trim()
        } else {
            t
        }
    }

    /**
     * Hash of [contentSignature] — the value the pipeline dedupes on and stores on the decision-history
     * row (so the raw text never is). SHA-256 over a per-process random salt: wide enough that two
     * distinct notifications can't collide and have one silently dropped, and salted so the persisted /
     * clipboard value can't be brute-forced back to the (often short, templated) source text by anyone
     * reading it without the running process. One definition for both the dedupe gate and the persisted
     * column so they can't drift.
     */
    val contentHash: String
        get() = hashContent(contentSignature)

    companion object {
        // Stable within a process (so the dedupe gate and the detail-screen display agree), never persisted.
        private val hashSalt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

        private fun hashContent(signature: String): String {
            val digest = MessageDigest.getInstance("SHA-256").run {
                update(hashSalt)
                digest(signature.toByteArray(Charsets.UTF_8))
            }
            // 64 bits keeps the column / UI value compact while making accidental collisions negligible.
            return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
