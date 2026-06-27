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
)
