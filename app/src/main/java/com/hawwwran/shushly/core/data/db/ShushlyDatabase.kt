package com.hawwwran.shushly.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DecisionHistoryEntity::class, AppLearningEntity::class], version = 4, exportSchema = false)
abstract class ShushlyDatabase : RoomDatabase() {
    abstract fun decisionHistoryDao(): DecisionHistoryDao
    abstract fun appLearningDao(): AppLearningDao
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

/**
 * v2 -> v3: additive only. Creates the app_learnings table (behavior-steering). The DDL + index names
 * mirror what Room generates for [AppLearningEntity] so the runtime schema-identity check passes;
 * decision_history is untouched. No data loss.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_learnings` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`packageName` TEXT NOT NULL, " +
                "`appLabel` TEXT NOT NULL, " +
                "`desiredDecision` TEXT NOT NULL, " +
                "`digest` TEXT NOT NULL, " +
                "`createdAtMs` INTEGER NOT NULL, " +
                "`sourceHistoryId` INTEGER)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_learnings_packageName` ON `app_learnings` (`packageName`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_app_learnings_sourceHistoryId` ON `app_learnings` (`sourceHistoryId`)",
        )
    }
}

/**
 * v3 -> v4: additive only. Adds the nullable content-dedupe hash column (surfaced in decision detail);
 * existing rows default to NULL. No data loss — do not replace with destructive fallback.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE decision_history ADD COLUMN contentHash TEXT")
    }
}
