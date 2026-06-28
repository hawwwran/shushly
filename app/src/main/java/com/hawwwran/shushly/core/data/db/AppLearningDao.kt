package com.hawwwran.shushly.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppLearningDao {
    /** All learnings, ordered for the AI-tab list (by app, newest first within an app). */
    @Query("SELECT * FROM app_learnings ORDER BY appLabel COLLATE NOCASE ASC, createdAtMs DESC")
    fun observeAll(): Flow<List<AppLearningEntity>>

    /** The most recent learnings for one app, for prompt injection. */
    @Query("SELECT * FROM app_learnings WHERE packageName = :packageName ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun forPackage(packageName: String, limit: Int): List<AppLearningEntity>

    @Query("SELECT * FROM app_learnings WHERE sourceHistoryId = :sourceHistoryId LIMIT 1")
    suspend fun getBySource(sourceHistoryId: Long): AppLearningEntity?

    /** Replace on conflict so re-correcting a row (unique sourceHistoryId) updates in place. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AppLearningEntity): Long

    @Query("DELETE FROM app_learnings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM app_learnings WHERE sourceHistoryId = :sourceHistoryId")
    suspend fun deleteBySource(sourceHistoryId: Long)
}
