package com.hawwwran.shushly.core.data

import com.hawwwran.shushly.core.data.db.DecisionHistoryDao
import com.hawwwran.shushly.core.data.db.DecisionHistoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent decision history (Room-backed, spec §6.3/§10.2). The single source of truth for the
 * decision log; bounded by the 30-day purge rather than an in-memory cap.
 */
@Singleton
class DecisionHistoryRepository @Inject constructor(
    private val dao: DecisionHistoryDao,
) {
    suspend fun record(entity: DecisionHistoryEntity) = dao.insert(entity)

    fun observeRecent(limit: Int = DEFAULT_LIMIT): Flow<List<DecisionHistoryEntity>> =
        dao.observeRecent(limit)

    suspend fun getById(id: Long): DecisionHistoryEntity? = dao.getById(id)

    suspend fun clearAll() = dao.clearAll()

    suspend fun purgeOlderThan(cutoffMs: Long): Int = dao.deleteOlderThan(cutoffMs)

    companion object {
        const val DEFAULT_LIMIT = 200
    }
}
