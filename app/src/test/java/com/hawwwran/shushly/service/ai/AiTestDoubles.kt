package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.data.ApiKeyStore
import com.hawwwran.shushly.core.data.AppLearningRepository
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.data.db.AppLearningEntity
import com.hawwwran.shushly.core.model.AiProviderType
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.EligibilityMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** Returns a fixed snapshot; setters are no-ops (the decision/routing units only read). */
class FakeSettingsRepository(var current: AppSettings = AppSettings()) : SettingsRepository {
    override val settings: Flow<AppSettings> get() = flowOf(current)
    override suspend fun snapshot(): AppSettings = current
    // These two actually mutate the snapshot so manager tests can assert persistence + so reconcile()
    // reads the just-set value. The rest stay no-ops (not exercised).
    override suspend fun setSmartQuietMode(enabled: Boolean) {
        current = current.copy(smartQuietModeEnabled = enabled)
    }
    override suspend fun setActiveWhenLocked(on: Boolean) {
        current = current.copy(activeWhenLocked = on)
    }
    override suspend fun setVibrate(enabled: Boolean) {}
    override suspend fun setAlertSound(uri: String?) {}
    override suspend fun setEligibilityMode(mode: EligibilityMode) {}
    override suspend fun setSelectedPackages(packages: Set<String>) {}
    override suspend fun setAlwaysAlertPackages(packages: Set<String>) {}
    override suspend fun setZenRuleId(id: String?) {}
    override suspend fun setOnboardingComplete(complete: Boolean) {}
    override suspend fun setAiProvider(provider: AiProviderType) {}
    override suspend fun setAiModel(model: String) {}
    override suspend fun setAiVerified(verified: Boolean, atMs: Long?) {}
    override suspend fun setCustomAiInstruction(text: String?) {}
    override suspend fun setAiUnavailableSince(ms: Long?) {}
    override suspend fun setListenerConnectedSince(atMs: Long?) {}
}

class FakeApiKeyStore(var key: String? = null) : ApiKeyStore {
    override suspend fun get(): String? = key
    override suspend fun set(key: String?) {
        this.key = key
    }
}

/** In-memory [AppLearningRepository]; save() replaces any learning with the same sourceHistoryId. */
class FakeAppLearningRepository(
    initial: List<AppLearningEntity> = emptyList(),
) : AppLearningRepository {
    val items: MutableList<AppLearningEntity> = initial.toMutableList()
    private val flow = MutableStateFlow(items.toList())

    override fun observeAll(): Flow<List<AppLearningEntity>> = flow

    override suspend fun forPackage(packageName: String, limit: Int): List<AppLearningEntity> =
        items.filter { it.packageName == packageName }.sortedByDescending { it.createdAtMs }.take(limit)

    override suspend fun getBySource(sourceHistoryId: Long): AppLearningEntity? =
        items.firstOrNull { it.sourceHistoryId == sourceHistoryId }

    override suspend fun save(entity: AppLearningEntity): Long {
        entity.sourceHistoryId?.let { src -> items.removeAll { it.sourceHistoryId == src } }
        val id = if (entity.id == 0L) (items.maxOfOrNull { it.id } ?: 0L) + 1 else entity.id
        items.add(entity.copy(id = id))
        flow.value = items.toList()
        return id
    }

    override suspend fun deleteBySource(sourceHistoryId: Long) {
        items.removeAll { it.sourceHistoryId == sourceHistoryId }
        flow.value = items.toList()
    }

    override suspend fun deleteById(id: Long) {
        items.removeAll { it.id == id }
        flow.value = items.toList()
    }
}
