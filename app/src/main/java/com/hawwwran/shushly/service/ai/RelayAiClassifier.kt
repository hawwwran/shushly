package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.data.DeviceTokenStore
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.ClassificationResult
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import com.hawwwran.shushly.service.ai.relay.AppDto
import com.hawwwran.shushly.service.ai.relay.ClassifyRequestDto
import com.hawwwran.shushly.service.ai.relay.ClassifyResponseDto
import com.hawwwran.shushly.service.ai.relay.NotificationDto
import com.hawwwran.shushly.service.ai.relay.PolicyDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real classifier: POSTs redacted notification metadata to the relay (spec §9.4) and maps the
 * response. Any network/parse/config error is allowed to propagate — the pipeline catches it and
 * fails safe to silent (spec §3.4). Holds no OpenAI key; the relay does.
 */
@Singleton
open class RelayAiClassifier @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
    private val deviceTokenStore: DeviceTokenStore,
) : AiClassifier {

    override suspend fun classify(request: ClassificationRequest): ClassificationResult =
        withContext(Dispatchers.IO) {
            val current = settings.snapshot()
            val baseUrl = current.aiConnection.relayBaseUrl?.trim()
            val token = deviceTokenStore.get()?.trim()
            if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) {
                throw IllegalStateException("relay not configured")
            }

            val dto = ClassifyRequestDto(
                eventId = UUID.randomUUID().toString(),
                app = AppDto(packageName = request.packageName, label = request.appLabel),
                notification = NotificationDto(
                    title = request.title,
                    body = request.body,
                    category = request.category,
                    postedAt = request.postedAt.toString(),
                ),
                policy = PolicyDto(
                    userInstruction = current.customAiInstruction?.takeIf { it.isNotBlank() },
                ),
            )
            val payload = json.encodeToString(ClassifyRequestDto.serializer(), dto)
            val url = baseUrl.trimEnd('/') + CLASSIFY_PATH

            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            okHttpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    // Include the status, never the body (it may carry sensitive context).
                    throw IOException("relay returned HTTP ${response.code}")
                }
                val responseText = response.body.string()
                val responseDto = json.decodeFromString(ClassifyResponseDto.serializer(), responseText)
                responseDto.toClassificationResult()
            }
        }

    companion object {
        const val CLASSIFY_PATH = "/v1/classify-notification"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Maps a relay response to the app's [ClassificationResult]. `decision` must be alert/silent (else
 * we throw → fail safe). The `reason_code` is kept only when it is a known enum value AND matches
 * the decision's category (ALERT_* for alert, SILENT_* for silent); an unknown or cross-category
 * code falls back to the decision default, so an alert never carries a SILENT_* reason (and vice
 * versa). This mirrors the relay's own normalization. Confidence passes through untouched — the
 * pipeline owns the 0.80 alert threshold.
 */
internal fun ClassifyResponseDto.toClassificationResult(): ClassificationResult {
    val mappedDecision = when (decision) {
        "alert" -> Decision.ALERT
        "silent" -> Decision.SILENT
        else -> throw IllegalStateException("unexpected relay decision: $decision")
    }
    val prefix = if (mappedDecision == Decision.ALERT) "ALERT_" else "SILENT_"
    val parsed = runCatching { DecisionReasonCode.valueOf(reasonCode) }.getOrNull()
    val mappedReason = if (parsed != null && parsed.name.startsWith(prefix)) {
        parsed
    } else if (mappedDecision == Decision.ALERT) {
        DecisionReasonCode.ALERT_TIME_SENSITIVE_ACTION
    } else {
        DecisionReasonCode.SILENT_LOW_CONFIDENCE
    }
    return ClassificationResult(
        decision = mappedDecision,
        confidence = confidence,
        reasonCode = mappedReason,
        userVisibleReason = userVisibleReason,
        modelName = model,
        latencyMs = latencyMs,
    )
}
