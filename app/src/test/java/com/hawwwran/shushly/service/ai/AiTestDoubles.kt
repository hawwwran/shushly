package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.data.ApiKeyStore
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.AiProviderType
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.EligibilityMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Returns a fixed snapshot; setters are no-ops (the decision/routing units only read). */
class FakeSettingsRepository(var current: AppSettings = AppSettings()) : SettingsRepository {
    override val settings: Flow<AppSettings> get() = flowOf(current)
    override suspend fun snapshot(): AppSettings = current
    override suspend fun setSmartQuietMode(enabled: Boolean) {}
    override suspend fun setVibrate(enabled: Boolean) {}
    override suspend fun setAlertSound(uri: String?) {}
    override suspend fun setSimulationMode(enabled: Boolean) {}
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
