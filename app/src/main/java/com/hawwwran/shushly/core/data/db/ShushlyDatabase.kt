package com.hawwwran.shushly.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [DecisionHistoryEntity::class], version = 1, exportSchema = false)
abstract class ShushlyDatabase : RoomDatabase() {
    abstract fun decisionHistoryDao(): DecisionHistoryDao
}
