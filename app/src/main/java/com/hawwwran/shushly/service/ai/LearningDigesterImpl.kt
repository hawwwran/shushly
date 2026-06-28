package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.BuildConfig
import com.hawwwran.shushly.core.data.ApiKeyStore
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.AiProviderType
import com.hawwwran.shushly.core.model.Decision
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the verified key/model and asks the provider for a digest (mirrors [DirectAiClassifier] +
 * [RoutingAiClassifier]): direct OpenAI when a key is set AND verified; otherwise a debug build
 * returns a deterministic local phrase and a release build throws. The key store is read only when
 * verified, so a Keystore init failure can't defeat the debug path.
 */
@Singleton
class LearningDigesterImpl @Inject constructor(
    private val settings: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val openAiProvider: OpenAiProvider,
) : LearningDigester {

    override suspend fun digest(
        appLabel: String,
        title: String?,
        body: String?,
        category: String?,
        desiredDecision: Decision,
    ): String {
        val conn = settings.snapshot().aiConnection
        val apiKey = if (conn.isVerified) apiKeyStore.get()?.trim() else null
        if (conn.isVerified && !apiKey.isNullOrBlank()) {
            val provider: AiProvider = when (conn.provider) {
                AiProviderType.OPENAI -> openAiProvider
            }
            return provider.summarizeForLearning(appLabel, title, body, category, desiredDecision, apiKey, conn.model)
        }
        if (BuildConfig.USE_FAKE_CLASSIFIER) return fakeDigest(title, body)
        throw IllegalStateException("AI not configured")
    }

    private fun fakeDigest(title: String?, body: String?): String =
        listOfNotNull(title, body).joinToString(" ").trim()
            .split(Regex("\\s+")).filter { it.isNotBlank() }.take(4).joinToString(" ")
            .ifBlank { "notification" }
}
