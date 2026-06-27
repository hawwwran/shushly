package com.hawwwran.shushly.core.model

/** How the app reaches the classifier. DIRECT_KEY_DEVELOPMENT_ONLY is reserved for a later task. */
enum class AiConnectionMode { RELAY_BACKEND, DIRECT_KEY_DEVELOPMENT_ONLY }

/**
 * AI-connection settings (spec §10.2). The device token is a credential and is NOT part of this
 * state — it lives in Keystore-backed storage (see DeviceTokenStore).
 */
data class AiConnectionState(
    val mode: AiConnectionMode = AiConnectionMode.RELAY_BACKEND,
    val relayBaseUrl: String? = null,
    val isVerified: Boolean = false,
    val lastVerifiedAtMs: Long? = null,
)
