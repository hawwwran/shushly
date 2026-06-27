package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.data.DeviceTokenStore
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.AiConnectionMode
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.EligibilityMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Returns a fixed snapshot; setters are no-ops (the relay/routing units only read). */
class FakeSettingsRepository(var current: AppSettings = AppSettings()) : SettingsRepository {
    override val settings: Flow<AppSettings> get() = flowOf(current)
    override suspend fun snapshot(): AppSettings = current
    override suspend fun setSmartQuietMode(enabled: Boolean) {}
    override suspend fun setVibrate(enabled: Boolean) {}
    override suspend fun setSimulationMode(enabled: Boolean) {}
    override suspend fun setEligibilityMode(mode: EligibilityMode) {}
    override suspend fun setSelectedPackages(packages: Set<String>) {}
    override suspend fun setZenRuleId(id: String?) {}
    override suspend fun setOnboardingComplete(complete: Boolean) {}
    override suspend fun setRelayBaseUrl(url: String?) {}
    override suspend fun setAiConnectionMode(mode: AiConnectionMode) {}
    override suspend fun setAiVerified(verified: Boolean, atMs: Long?) {}
    override suspend fun setCustomAiInstruction(text: String?) {}
}

class FakeDeviceTokenStore(var token: String? = null) : DeviceTokenStore {
    override suspend fun get(): String? = token
    override suspend fun set(token: String?) {
        this.token = token
    }
}
