package com.hawwwran.shushly.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One user-taught steering hint for a single app, created from a decision-history correction.
 *
 * Privacy: [digest] is an AI-written, generalised, no-PII topic phrase — never raw notification text.
 * Lives in its own table so the 30-day history purge ([com.hawwwran.shushly.service.work.HistoryPurgeWorker])
 * never deletes it. [sourceHistoryId] links back to the decision row it came from (unique, so
 * re-tapping the same row replaces its learning rather than duplicating); after that row is purged the
 * link dangles harmlessly because the learning is self-contained.
 */
@Entity(
    tableName = "app_learnings",
    indices = [
        Index("packageName"),
        Index(value = ["sourceHistoryId"], unique = true),
    ],
)
data class AppLearningEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    /** [com.hawwwran.shushly.core.model.Decision].ALERT.name or SILENT.name — the behaviour the user wants. */
    val desiredDecision: String,
    val digest: String,
    val createdAtMs: Long,
    val sourceHistoryId: Long?,
)
