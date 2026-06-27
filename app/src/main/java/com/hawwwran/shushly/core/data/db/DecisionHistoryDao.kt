package com.hawwwran.shushly.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DecisionHistoryDao {

    @Insert
    suspend fun insert(entity: DecisionHistoryEntity)

    @Query("SELECT * FROM decision_history ORDER BY createdAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DecisionHistoryEntity>>

    @Query("SELECT * FROM decision_history WHERE id = :id")
    suspend fun getById(id: Long): DecisionHistoryEntity?

    @Query("DELETE FROM decision_history WHERE createdAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM decision_history")
    suspend fun clearAll()
}
