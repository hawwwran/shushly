package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.BuildConfig
import com.hawwwran.shushly.core.data.DeviceTokenStore
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.ClassificationResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects the classifier per call (spec §8.8): the relay is used only when the connection is
 * verified AND has a non-blank base URL AND a non-blank token; otherwise debug builds fall back to
 * the deterministic [FakeAiClassifier] and a release build throws → the pipeline fails safe to
 * silent. Gating on `isVerified` (not mere presence) means a half-entered config or a relay that
 * failed its last Test is never used live while the UI says "not connected".
 */
@Singleton
class RoutingAiClassifier @Inject constructor(
    private val fake: FakeAiClassifier,
    private val relay: RelayAiClassifier,
    private val settings: SettingsRepository,
    private val deviceTokenStore: DeviceTokenStore,
) : AiClassifier {

    override suspend fun classify(request: ClassificationRequest): ClassificationResult {
        val aiConnection = settings.snapshot().aiConnection
        val baseUrl = aiConnection.relayBaseUrl?.trim()
        // Check verified + base URL first; read the credential store last (and only then). With no
        // verified relay, a Keystore/EncryptedSharedPreferences init failure must not defeat the
        // debug fake path.
        val relayConfigured = if (!aiConnection.isVerified || baseUrl.isNullOrBlank()) {
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
