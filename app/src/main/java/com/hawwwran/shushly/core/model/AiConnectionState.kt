package com.hawwwran.shushly.core.model

/**
 * AI-connection settings. The API key is a credential and is NOT part of this state — it lives in
 * Keystore-backed storage (see [com.hawwwran.shushly.core.data.ApiKeyStore]). The app calls the
 * provider directly with the user's own key (D12).
 */
data class AiConnectionState(
    val provider: AiProviderType = AiProviderType.OPENAI,
    val model: String = DEFAULT_MODEL,
    val isVerified: Boolean = false,
    val lastVerifiedAtMs: Long? = null,
) {
    companion object {
        const val DEFAULT_MODEL = "gpt-4.1-mini"
    }
}
