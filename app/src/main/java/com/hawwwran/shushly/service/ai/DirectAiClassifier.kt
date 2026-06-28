package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.data.ApiKeyStore
import com.hawwwran.shushly.core.data.AppLearningRepository
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.data.db.AppLearningEntity
import com.hawwwran.shushly.core.model.AiProviderType
import com.hawwwran.shushly.core.model.AppLearning
import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.ClassificationResult
import com.hawwwran.shushly.core.model.Decision
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies by calling the configured provider directly with the user's own key. Reads the
 * provider + model from settings, the key from [ApiKeyStore], the steer instruction, and the user's
 * per-app learnings; throws if there is no verified key (the pipeline then fails safe to silent).
 * Extensible: add a provider impl and a branch here.
 */
@Singleton
open class DirectAiClassifier @Inject constructor(
    private val settings: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val openAiProvider: OpenAiProvider,
    private val appLearnings: AppLearningRepository,
) : AiClassifier {

    override suspend fun classify(request: ClassificationRequest): ClassificationResult {
        val current = settings.snapshot()
        val conn = current.aiConnection
        val apiKey = apiKeyStore.get()?.trim()
        if (!conn.isVerified || apiKey.isNullOrBlank()) {
            throw IllegalStateException("AI not configured")
        }
        // The user's past corrections for this app, replayed as advisory guidance. A DB hiccup must
        // never break classification, so degrade to none.
        val learnings = runCatching { appLearnings.forPackage(request.packageName) }
            .getOrDefault(emptyList())
            .mapNotNull { it.toModel() }
        val provider: AiProvider = when (conn.provider) {
            AiProviderType.OPENAI -> openAiProvider
        }
        return provider.classify(
            request = request.copy(appLearnings = learnings),
            apiKey = apiKey,
            model = conn.model,
            userInstruction = current.customAiInstruction?.takeIf { it.isNotBlank() },
        )
    }

    private fun AppLearningEntity.toModel(): AppLearning? {
        val decision = runCatching { Decision.valueOf(desiredDecision) }.getOrNull() ?: return null
        return AppLearning(decision, digest)
    }
}
