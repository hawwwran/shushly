package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.data.ApiKeyStore
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.AiProviderType
import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.ClassificationResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies by calling the configured provider directly with the user's own key. Reads the
 * provider + model from settings, the key from [ApiKeyStore], and the steer instruction; throws if
 * there is no verified key (the pipeline then fails safe to silent). Extensible: add a provider impl
 * and a branch here.
 */
@Singleton
open class DirectAiClassifier @Inject constructor(
    private val settings: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val openAiProvider: OpenAiProvider,
) : AiClassifier {

    override suspend fun classify(request: ClassificationRequest): ClassificationResult {
        val current = settings.snapshot()
        val conn = current.aiConnection
        val apiKey = apiKeyStore.get()?.trim()
        if (!conn.isVerified || apiKey.isNullOrBlank()) {
            throw IllegalStateException("AI not configured")
        }
        val provider: AiProvider = when (conn.provider) {
            AiProviderType.OPENAI -> openAiProvider
        }
        return provider.classify(
            request = request,
            apiKey = apiKey,
            model = conn.model,
            userInstruction = current.customAiInstruction?.takeIf { it.isNotBlank() },
        )
    }
}
