package com.hawwwran.shushly.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One recorded decision (spec §6.3). Privacy-minimal: the same fields the in-memory log held, with
 * NO raw notification title/body, preview, or extras. Enums are stored as their `.name` (Strings)
 * so no type converters are needed. Phase-2 fields (model/latency/redaction) are intentionally
 * omitted until the relay lands.
 */
@Entity(tableName = "decision_history")
data class DecisionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtMs: Long,
    val packageName: String,
    val appLabel: String,
    val notificationKeyHash: String?,
    val decision: String,
    val reasonCode: String,
    val userVisibleReason: String?,
    val aiCalled: Boolean,
    val wasAlerted: Boolean,
    // Local-only user feedback (§14.3): null / "SHOULD_ALERT" / "SHOULD_SILENT". Added in schema v2.
    val userFeedback: String? = null,
    // Content-dedupe hash (salted SHA-256 of app + title + body); surfaced in the detail screen. Added
    // in schema v4; null for rows recorded before v4. Privacy-safe: a salted one-way hash, never the raw text.
    val contentHash: String? = null,
    // DEBUG-ONLY (temporary, schema v5): the raw title/body, kept ONLY to diagnose why two seemingly
    // identical notifications produce different content hashes. Written only in debug builds (always null
    // in release, preserving the no-raw-text rule). Remove these two columns once dedupe hashing is sorted.
    val debugTitle: String? = null,
    val debugBody: String? = null,
)
