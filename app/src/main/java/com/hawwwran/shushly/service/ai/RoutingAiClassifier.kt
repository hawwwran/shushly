package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.BuildConfig
import com.hawwwran.shushly.core.data.DeviceTokenStore
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.ClassificationResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects the classifier per call (spec §8.8): a configured relay (non-blank base URL AND token)
 * wins; otherwise debug builds fall back to the deterministic [FakeAiClassifier]; a release build
 * with no relay throws → the pipeline fails safe to silent.
 */
@Singleton
class RoutingAiClassifier @Inject constructor(
    private val fake: FakeAiClassifier,
    private val relay: RelayAiClassifier,
    private val settings: SettingsRepository,
    private val deviceTokenStore: DeviceTokenStore,
) : AiClassifier {

    override suspend fun classify(request: ClassificationRequest): ClassificationResult {
        val baseUrl = settings.snapshot().aiConnection.relayBaseUrl?.trim()
        // Only touch the credential store when a base URL is set: with no relay configured a
        // Keystore/EncryptedSharedPreferences init failure must not defeat the debug fake path.
        val relayConfigured = if (baseUrl.isNullOrBlank()) {
            false
        } else {
            !deviceTokenStore.get()?.trim().isNullOrBlank()
        }
        return when {
            relayConfigured -> relay.classify(request)
            BuildConfig.USE_FAKE_CLASSIFIER -> fake.classify(request)
            else -> throw IllegalStateException("AI not configured")
        }
    }
}
