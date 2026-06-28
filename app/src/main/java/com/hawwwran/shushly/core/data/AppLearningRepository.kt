package com.hawwwran.shushly.core.data

import com.hawwwran.shushly.core.data.db.AppLearningDao
import com.hawwwran.shushly.core.data.db.AppLearningEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-app steering hints taught by the user (see behavior-steering plan). An interface so the
 * ViewModels and the AI layer can be unit-tested with a fake.
 */
interface AppLearningRepository {
    fun observeAll(): Flow<List<AppLearningEntity>>
    suspend fun forPackage(packageName: String, limit: Int = DEFAULT_INJECT_LIMIT): List<AppLearningEntity>
    suspend fun getBySource(sourceHistoryId: Long): AppLearningEntity?
    suspend fun save(entity: AppLearningEntity): Long
    suspend fun deleteBySource(sourceHistoryId: Long)
    suspend fun deleteById(id: Long)

    companion object {
        /** Cap on learnings injected per app per request — bounds tokens and prompt-injection surface. */
        const val DEFAULT_INJECT_LIMIT = 12
    }
}

@Singleton
class AppLearningRepositoryImpl @Inject constructor(
    private val dao: AppLearningDao,
) : AppLearningRepository {
    override fun observeAll(): Flow<List<AppLearningEntity>> = dao.observeAll()
    override suspend fun forPackage(packageName: String, limit: Int) = dao.forPackage(packageName, limit)
    override suspend fun getBySource(sourceHistoryId: Long) = dao.getBySource(sourceHistoryId)
    override suspend fun save(entity: AppLearningEntity): Long = dao.insert(entity)
    override suspend fun deleteBySource(sourceHistoryId: Long) = dao.deleteBySource(sourceHistoryId)
    override suspend fun deleteById(id: Long) = dao.deleteById(id)
}
