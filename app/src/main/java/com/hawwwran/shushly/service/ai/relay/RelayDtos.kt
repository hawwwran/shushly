package com.hawwwran.shushly.service.ai.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the relay's `/v1/classify-notification` contract. Field names must match
 * `relay/src/schema.ts` exactly (it is the source of truth). Serialize/deserialize with a
 * `Json { ignoreUnknownKeys = true; explicitNulls = false }` instance.
 */
@Serializable
data class ClassifyRequestDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("event_id") val eventId: String,
    val app: AppDto,
    val notification: NotificationDto,
    val policy: PolicyDto,
)

@Serializable
data class AppDto(
    @SerialName("package_name") val packageName: String,
    val label: String,
)

@Serializable
data class NotificationDto(
    val title: String?,
    val body: String?,
    val category: String?,
    @SerialName("posted_at") val postedAt: String?,
)

@Serializable
data class PolicyDto(
    val locale: String = "en",
    @SerialName("default_on_ambiguity") val defaultOnAmbiguity: String = "silent",
    @SerialName("user_instruction") val userInstruction: String? = null,
)

@Serializable
data class ClassifyResponseDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("event_id") val eventId: String? = null,
    val decision: String,
    val confidence: Double,
    @SerialName("reason_code") val reasonCode: String,
    @SerialName("user_visible_reason") val userVisibleReason: String? = null,
    val model: String? = null,
    @SerialName("latency_ms") val latencyMs: Long? = null,
)
