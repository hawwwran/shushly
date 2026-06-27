package com.hawwwran.shushly.core.data

import com.hawwwran.shushly.core.data.db.DecisionHistoryDao
import com.hawwwran.shushly.core.data.db.DecisionHistoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent decision history (Room-backed, spec §6.3/§10.2). The single source of truth for the
 * decision log; bounded by the 30-day purge rather than an in-memory cap. An interface so the
 * pipeline/history units can be unit-tested with a fake.
 */
interface DecisionHistoryRepository {
    suspend fun record(entity: DecisionHistoryEntity)
    fun observeRecent(limit: Int = DEFAULT_LIMIT): Flow<List<DecisionHistoryEntity>>
    suspend fun getById(id: Long): DecisionHistoryEntity?
    suspend fun setFeedback(id: Long, feedback: String?)
    suspend fun clearAll()
    suspend fun purgeOlderThan(cutoffMs: Long): Int

    companion object {
        const val DEFAULT_LIMIT = 200
    }
}

/** Room/DAO-backed [DecisionHistoryRepository]. */
@Singleton
class DecisionHistoryRepositoryImpl @Inject constructor(
    private val dao: DecisionHistoryDao,
) : DecisionHistoryRepository {
    override suspend fun record(entity: DecisionHistoryEntity) {
        dao.insert(entity)
    }

    override fun observeRecent(limit: Int): Flow<List<DecisionHistoryEntity>> =
        dao.observeRecent(limit)

    override suspend fun getById(id: Long): DecisionHistoryEntity? = dao.getById(id)

    override suspend fun setFeedback(id: Long, feedback: String?) {
        dao.setFeedback(id, feedback)
    }

    override suspend fun clearAll() {
        dao.clearAll()
    }

    override suspend fun purgeOlderThan(cutoffMs: Long): Int = dao.deleteOlderThan(cutoffMs)
}
