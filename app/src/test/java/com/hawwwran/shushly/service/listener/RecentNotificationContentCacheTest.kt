package com.hawwwran.shushly.service.listener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentNotificationContentCacheTest {

    private var now = 1_000L
    private val cache = InMemoryRecentNotificationContentCache { now }

    private fun put(key: String, pkg: String = "com.example.app") =
        cache.put(key, pkg, "Example", "title", "body", "msg")

    @Test
    fun returnsRecentlyPutContent() {
        put("k")
        val c = cache.get("k", "com.example.app")
        assertEquals("title", c?.title)
        assertEquals("body", c?.body)
        assertEquals("Example", c?.appLabel)
    }

    @Test
    fun servesUntilExactlyRetentionWindow() {
        put("k")
        now += RecentNotificationContentCache.RETENTION_MS // boundary: not yet older-than
        assertEquals("title", cache.get("k", "com.example.app")?.title)
    }

    @Test
    fun expiresAfterRetentionWindow() {
        put("k")
        now += RecentNotificationContentCache.RETENTION_MS + 1
        assertNull(cache.get("k", "com.example.app"))
    }

    @Test
    fun nullWhenPackageMismatch() {
        put("k", pkg = "com.a")
        assertNull(cache.get("k", "com.b"))
    }

    @Test
    fun nullWhenAbsent() {
        assertNull(cache.get("missing", "com.example.app"))
    }

    @Test
    fun evictsOldestBeyondCap() {
        val n = RecentNotificationContentCache.MAX_ENTRIES + 50
        repeat(n) { put("k$it") }
        assertNull(cache.get("k0", "com.example.app")) // eldest evicted
        assertEquals("title", cache.get("k${n - 1}", "com.example.app")?.title) // newest kept
    }
}
