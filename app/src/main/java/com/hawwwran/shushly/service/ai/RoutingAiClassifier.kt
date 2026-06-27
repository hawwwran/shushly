package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.BuildConfig
import com.hawwwran.shushly.core.data.ApiKeyStore
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.ClassificationResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects the classifier per call: the [DirectAiClassifier] is used only when a key is set AND the
 * connection is verified; otherwise debug builds fall back to the deterministic [FakeAiClassifier]
 * and a release build throws → the pipeline fails safe to silent. Gating on `isVerified` (not mere
 * presence) means a half-entered or failed key is never used live while the UI says "not connected".
 */
@Singleton
class RoutingAiClassifier @Inject constructor(
    private val fake: FakeAiClassifier,
    private val direct: DirectAiClassifier,
    private val settings: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
) : AiClassifier {

    override suspend fun classify(request: ClassificationRequest): ClassificationResult {
        val conn = settings.snapshot().aiConnection
        // Check verified first; read the key store last (and only then). With no verified key, a
        // Keystore/EncryptedSharedPreferences init failure must not defeat the debug fake path.
        val directConfigured = if (!conn.isVerified) {
            false
        } else {
            !apiKeyStore.get()?.trim().isNullOrBlank()
        }
        return when {
            directConfigured -> direct.classify(request)
            BuildConfig.USE_FAKE_CLASSIFIER -> fake.classify(request)
            else -> throw IllegalStateException("AI not configured")
        }
    }
}
