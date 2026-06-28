package com.hawwwran.shushly.core.policy

import android.app.Notification
import com.hawwwran.shushly.core.model.ExtractedNotification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ProtectedSourcePolicyTest {

    /** An ordinary, non-protected notification by default; override one field per test. */
    private fun notification(
        packageName: String = "com.whatsapp",
        title: String? = null,
        body: String? = "Lunch?",
        category: String? = null,
    ) = ExtractedNotification(
        notificationKey = "key",
        packageName = packageName,
        appLabel = "App",
        postedAt = Instant.EPOCH,
        title = title,
        body = body,
        category = category,
        isPersistent = false,
        isGroupSummary = false,
        contentIntent = null,
    )

    // isProtectedPackage

    @Test
    fun isProtectedPackage_knownProtected_true() {
        assertTrue(ProtectedSourcePolicy.isProtectedPackage("com.android.dialer"))
    }

    @Test
    fun isProtectedPackage_ordinary_false() {
        assertFalse(ProtectedSourcePolicy.isProtectedPackage("com.whatsapp"))
    }

    // isProtected(notification) — one independent reason per test

    @Test
    fun isProtected_byPackage() {
        assertTrue(ProtectedSourcePolicy.isProtected(notification(packageName = "com.android.dialer")))
    }

    @Test
    fun isProtected_byCallCategory() {
        assertTrue(ProtectedSourcePolicy.isProtected(notification(category = Notification.CATEGORY_CALL)))
    }

    @Test
    fun isProtected_byAlarmCategory() {
        assertTrue(ProtectedSourcePolicy.isProtected(notification(category = Notification.CATEGORY_ALARM)))
    }

    @Test
    fun isProtected_byOtpText() {
        assertTrue(ProtectedSourcePolicy.isProtected(notification(body = "Your verification code is 481920")))
    }

    @Test
    fun isProtected_ordinaryNotification_false() {
        assertFalse(ProtectedSourcePolicy.isProtected(notification()))
    }

    // looksLikeOtp — keyword AND a 4-8 digit standalone code both required

    @Test
    fun looksLikeOtp_keywordAndDigits_true() {
        assertTrue(ProtectedSourcePolicy.looksLikeOtp("Your verification code is 481920"))
    }

    @Test
    fun looksLikeOtp_keywordWithoutDigits_false() {
        assertFalse(ProtectedSourcePolicy.looksLikeOtp("verification code"))
    }

    @Test
    fun looksLikeOtp_digitsWithoutKeyword_false() {
        assertFalse(ProtectedSourcePolicy.looksLikeOtp("1234"))
    }

    @Test
    fun looksLikeOtp_null_false() {
        assertFalse(ProtectedSourcePolicy.looksLikeOtp(null))
    }

    @Test
    fun looksLikeOtp_empty_false() {
        assertFalse(ProtectedSourcePolicy.looksLikeOtp(""))
    }

    @Test
    fun looksLikeOtp_shortKeywordForm_true() {
        assertTrue(ProtectedSourcePolicy.looksLikeOtp("OTP: 99213"))
    }
}
