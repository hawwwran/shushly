package com.hawwwran.shushly.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

/** Content-signature normalization (§7.5 dedupe): stripping an app-name title prefix. */
class ExtractedNotificationTest {

    private fun notif(appLabel: String, title: String?, body: String?) = ExtractedNotification(
        notificationKey = "k",
        packageName = "com.whatsapp",
        appLabel = appLabel,
        postedAt = Instant.EPOCH,
        title = title,
        body = body,
        category = null,
        isPersistent = false,
        isGroupSummary = false,
        contentIntent = null,
    )

    @Test
    fun appNamePrefixedTitle_hashesSameAsPlain() {
        // Observed: WhatsApp posts one message as "WhatsApp: Alice" and again as "Alice"; both must
        // dedupe as a single event, so their content hashes have to match.
        val prefixed = notif("WhatsApp", "WhatsApp: Alice", "see you")
        val plain = notif("WhatsApp", "Alice", "see you")
        assertEquals(plain.contentHash, prefixed.contentHash)
    }

    @Test
    fun caseInsensitivePrefix_isStripped() {
        val prefixed = notif("WhatsApp", "whatsapp: Alice", "see you")
        val plain = notif("WhatsApp", "Alice", "see you")
        assertEquals(plain.contentHash, prefixed.contentHash)
    }

    @Test
    fun distinctSendersUnderSamePrefix_stayDistinct() {
        // Only the common prefix is removed; the distinguishing remainder must keep two senders apart.
        val alice = notif("WhatsApp", "WhatsApp: Alice", "see you")
        val bob = notif("WhatsApp", "WhatsApp: Bob", "see you")
        assertNotEquals(alice.contentHash, bob.contentHash)
    }

    @Test
    fun labelOnlyMidTitle_isNotStripped() {
        // No false stripping: the label must be a leading prefix, not merely contained in the title.
        val a = notif("WhatsApp", "Re: WhatsApp down", "x")
        val b = notif("WhatsApp", "down", "x")
        assertNotEquals(a.contentHash, b.contentHash)
    }
}
