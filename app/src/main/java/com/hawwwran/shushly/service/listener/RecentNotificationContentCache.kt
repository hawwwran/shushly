package com.hawwwran.shushly.service.listener

import javax.inject.Inject
import javax.inject.Singleton

/**
 * A short-lived, in-memory cache of recently classified notification content, keyed by the same
 * `notificationKeyHash` stored on the decision-history row. It exists for one purpose: so the user can,
 * on the decision-detail screen, ask the AI to summarise a notification into a steering hint shortly
 * after it happened.
 *
 * Privacy: raw title/body live ONLY here, in RAM, for at most [RETENTION_MS] (3 h), and are never
 * written to disk — the "never persist raw notification text" invariant is unchanged. The cache is
 * lost on process death by design: once the content is gone the steering buttons hide.
 */
interface RecentNotificationContentCache {
    data class Cached(
        val packageName: String,
        val appLabel: String,
        val title: String?,
        val body: String?,
        val category: String?,
    )

    fun put(keyHash: String, packageName: String, appLabel: String, title: String?, body: String?, category: String?)

    /** The cached content, or null if absent, older than [RETENTION_MS], or the package doesn't match. */
    fun get(keyHash: String, packageName: String): Cached?

    companion object {
        const val RETENTION_MS: Long = 3L * 60 * 60 * 1000
        const val MAX_ENTRIES = 256
    }
}

/**
 * The primary constructor takes a clock so the 3 h TTL is unit-testable; Hilt uses the no-arg
 * [@Inject] secondary constructor with the real clock.
 */
@Singleton
class InMemoryRecentNotificationContentCache(
    private val nowMs: () -> Long,
) : RecentNotificationContentCache {

    @Inject constructor() : this(System::currentTimeMillis)

    private data class Entry(val cached: RecentNotificationContentCache.Cached, val storedAtMs: Long)

    private val lock = Any()

    // Insertion-ordered with capped size: the eldest entry is dropped once over MAX_ENTRIES.
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > RecentNotificationContentCache.MAX_ENTRIES
    }

    override fun put(
        keyHash: String,
        packageName: String,
        appLabel: String,
        title: String?,
        body: String?,
        category: String?,
    ) {
        val entry = Entry(
            RecentNotificationContentCache.Cached(packageName, appLabel, title, body, category),
            nowMs(),
        )
        synchronized(lock) { entries[keyHash] = entry }
    }

    override fun get(keyHash: String, packageName: String): RecentNotificationContentCache.Cached? {
        val now = nowMs()
        synchronized(lock) {
            val entry = entries[keyHash] ?: return null
            if (now - entry.storedAtMs > RecentNotificationContentCache.RETENTION_MS) {
                entries.remove(keyHash)
                return null
            }
            return entry.cached.takeIf { it.packageName == packageName }
        }
    }
}
