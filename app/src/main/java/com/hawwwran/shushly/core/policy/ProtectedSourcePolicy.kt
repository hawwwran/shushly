package com.hawwwran.shushly.core.policy

import android.app.Notification
import com.hawwwran.shushly.core.model.ExtractedNotification

/**
 * Conservative, code-owned policy for sources that must never be analysed or re-alerted
 * (spec §3.5). The package list is a best-effort seed; the category/flag and OTP-shape
 * checks are the robust part. Editable only by app update in V1.
 */
object ProtectedSourcePolicy {

    private val PROTECTED_PACKAGES: Set<String> = setOf(
        // telephony / dialer
        "com.android.phone", "com.android.server.telecom",
        "com.android.dialer", "com.google.android.dialer",
        // alarms / clock
        "com.android.deskclock", "com.google.android.deskclock",
        // emergency / cell broadcast
        "com.android.cellbroadcastreceiver", "com.android.cellbroadcastreceiver.module",
        // authenticators / password managers
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator", "com.lastpass.lpandroid",
        "com.x8bit.bitwarden", "com.bitwarden.x8",
        // wallet / payments
        "com.google.android.apps.walletnfcrel",
    )

    private val PROTECTED_CATEGORIES: Set<String> = setOf(
        Notification.CATEGORY_CALL,
        Notification.CATEGORY_ALARM,
    )

    private val OTP_KEYWORDS = listOf(
        "otp", "one-time", "one time", "verification code",
        "security code", "passcode", "login code", "auth code",
    )
    private val CODE_REGEX = Regex("""\b\d{4,8}\b""")

    fun isProtectedPackage(packageName: String): Boolean = packageName in PROTECTED_PACKAGES

    fun isProtected(notification: ExtractedNotification): Boolean {
        if (notification.packageName in PROTECTED_PACKAGES) return true
        if (notification.category in PROTECTED_CATEGORIES) return true
        if (notification.isOngoing) return true
        if (looksLikeOtp(notification.combinedText)) return true
        return false
    }

    /** Crude OTP detection: an OTP keyword together with a short standalone numeric code. */
    fun looksLikeOtp(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase()
        val hasKeyword = OTP_KEYWORDS.any { it in lower }
        return hasKeyword && CODE_REGEX.containsMatchIn(text)
    }
}
