package com.hawwwran.shushly.core.model

/**
 * Which AI backend the app calls directly. OpenAI-only for now; adding another provider is a new
 * [com.hawwwran.shushly.service.ai.AiProvider] impl + a value here, with no pipeline changes (D12).
 */
enum class AiProviderType { OPENAI }
