package com.hawwwran.shushly.service.listener

import com.hawwwran.shushly.core.data.DecisionHistoryRepository
import com.hawwwran.shushly.core.data.SeenAppsRepository
import com.hawwwran.shushly.core.data.db.DecisionHistoryEntity
import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.ClassificationResult
import com.hawwwran.shushly.service.ai.AiClassifier
import com.hawwwran.shushly.service.alerting.CriticalAlertSounder
import com.hawwwran.shushly.service.quietmode.LockStateProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Records how often an alert sounded and the last vibrate flag + sound URI passed. */
class RecordingSounder : CriticalAlertSounder {
    var callCount = 0
        private set
    var lastVibrate: Boolean? = null
        private set
    var lastSoundUri: String? = null
        private set

    override fun playAlert(vibrate: Boolean, soundUri: String?) {
        callCount++
        lastVibrate = vibrate
        lastSoundUri = soundUri
    }
}

/** Controllable lock state for the pipeline gate. */
class FakeLockStateProvider(var inUse: Boolean = false) : LockStateProvider {
    override fun isInUse(): Boolean = inUse
}

/** Captures recorded packages (the pipeline records but never reads seen counts in these tests). */
class FakeSeenAppsRepository : SeenAppsRepository {
    val recorded = mutableListOf<String>()
    override val seenCounts: Flow<Map<String, Int>> = flowOf(emptyMap())
    override fun record(packageName: String) {
        recorded.add(packageName)
    }
}

/** Captures every recorded decision; other methods are no-ops/empty. */
class RecordingHistoryRepository : DecisionHistoryRepository {
    val recorded = mutableListOf<DecisionHistoryEntity>()
    val last: DecisionHistoryEntity? get() = recorded.lastOrNull()

    override suspend fun record(entity: DecisionHistoryEntity) {
        recorded.add(entity)
    }

    override fun observeRecent(limit: Int): Flow<List<DecisionHistoryEntity>> = flowOf(recorded.toList())
    override suspend fun getById(id: Long): DecisionHistoryEntity? = recorded.firstOrNull { it.id == id }
    override suspend fun setFeedback(id: Long, feedback: String?) {
        val i = recorded.indexOfFirst { it.id == id }
        if (i >= 0) recorded[i] = recorded[i].copy(userFeedback = feedback)
    }
    override suspend fun clearAll() {
        recorded.clear()
    }
    override suspend fun purgeOlderThan(cutoffMs: Long): Int = 0
}

/** Returns a fixed [ClassificationResult], or throws [error] (to test fail-to-silent). */
class ProgrammableClassifier(
    private val result: ClassificationResult? = null,
    private val error: Throwable? = null,
) : AiClassifier {
    var callCount = 0
        private set

    override suspend fun classify(request: ClassificationRequest): ClassificationResult {
        callCount++
        error?.let { throw it }
        return result ?: error("ProgrammableClassifier: no result configured")
    }
}
