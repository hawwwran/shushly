package com.hawwwran.shushly.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DecisionHistoryEntity::class], version = 2, exportSchema = false)
abstract class ShushlyDatabase : RoomDatabase() {
    abstract fun decisionHistoryDao(): DecisionHistoryDao
}

/**
 * v1 -> v2: additive only. Adds the nullable local-feedback column (§14.3); existing rows are
 * preserved (the new column defaults to NULL). No data loss — do not replace with destructive fallback.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE decision_history ADD COLUMN userFeedback TEXT")
    }
}
