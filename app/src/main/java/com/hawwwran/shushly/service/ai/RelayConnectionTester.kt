package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.service.ai.relay.AuthCheckResponseDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * Tests relay reachability via `GET /v1/auth-check` (bearer auth, no OpenAI call). Used by the
 * AI-connection screen's "Test connection" button. Never logs the token.
 */
class RelayConnectionTester @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    sealed interface Result {
        data class Success(val model: String?) : Result
        data class Failure(val reason: String) : Result
    }

    suspend fun check(baseUrl: String, token: String): Result = withContext(Dispatchers.IO) {
        val url = baseUrl.trim().trimEnd('/') + "/v1/auth-check"
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${token.trim()}")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.Failure("HTTP ${response.code}")
                } else {
                    val dto = json.decodeFromString(AuthCheckResponseDto.serializer(), response.body.string())
                    Result.Success(dto.model)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IllegalArgumentException) {
            Result.Failure("Invalid relay URL")
        } catch (e: IOException) {
            Result.Failure("Couldn't reach the relay")
        } catch (e: SerializationException) {
            Result.Failure("Unexpected response from relay")
        }
    }
}
