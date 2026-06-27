package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class OpenAiProviderTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun provider() = OpenAiProvider(OkHttpClient(), json, server.url("/").toString())

    private val request = ClassificationRequest(
        packageName = "com.example.app",
        appLabel = "Example App",
        title = "Server down",
        body = "Prod is on fire",
        category = "msg",
        postedAt = Instant.parse("2026-06-27T12:00:00Z"),
    )

    /** Wraps a classification-JSON string as the `content` of an OpenAI chat-completions response. */
    private fun chatResponse(content: String, model: String = "gpt-4.1-mini-2025-06-01"): String {
        val escaped = JsonPrimitive(content).toString() // quoted + escaped JSON string literal
        return """{"model":"$model","choices":[{"message":{"role":"assistant","content":$escaped}}]}"""
    }

    private fun classification(
        decision: String,
        reason: String,
        confidence: String = "0.92",
        uvr: String = "Outage reported.",
    ) = """{"decision":"$decision","confidence":$confidence,"reason_code":"$reason","user_visible_reason":"$uvr"}"""

    private suspend fun assertThrowsAny(block: suspend () -> Unit) {
        var threw = false
        try {
            block()
        } catch (_: Throwable) {
            threw = true
        }
        assertTrue("expected an exception to propagate", threw)
    }

    @Test
    fun alert200_mapsResult_andSendsAuthPathModelAndInstruction() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(chatResponse(classification("alert", "ALERT_WORK_INCIDENT")))
                .build(),
        )

        val result = provider().classify(
            request = request,
            apiKey = "sk-test-123",
            model = "gpt-4.1-mini",
            userInstruction = "I'm an on-call SRE; treat PagerDuty/outage as critical",
        )

        assertEquals(Decision.ALERT, result.decision)
        assertEquals(DecisionReasonCode.ALERT_WORK_INCIDENT, result.reasonCode)
        assertEquals(0.92, result.confidence, 0.0001)
        assertEquals("gpt-4.1-mini-2025-06-01", result.modelName)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.target)
        assertEquals("Bearer sk-test-123", recorded.headers["Authorization"])
        val body = recorded.body?.utf8().orEmpty()
        assertTrue(body.contains("\"model\":\"gpt-4.1-mini\""))
        assertTrue(body.contains("json_schema"))
        assertTrue(body.contains("classification"))
        assertTrue(body.contains("PagerDuty")) // instruction folded into the system message
        assertTrue(body.contains("Example App")) // app label in the user message
    }

    @Test
    fun silentMarketing_mapsToSilent() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(chatResponse(classification("silent", "SILENT_MARKETING", confidence = "0.1")))
                .build(),
        )
        val result = provider().classify(request, "sk", "gpt-4.1-mini", null)
        assertEquals(Decision.SILENT, result.decision)
        assertEquals(DecisionReasonCode.SILENT_MARKETING, result.reasonCode)
    }

    @Test
    fun crossCategoryReason_onAlert_usesAlertDefault() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(chatResponse(classification("alert", "SILENT_ROUTINE")))
                .build(),
        )
        val result = provider().classify(request, "sk", "gpt-4.1-mini", null)
        assertEquals(Decision.ALERT, result.decision)
        assertEquals(DecisionReasonCode.ALERT_TIME_SENSITIVE_ACTION, result.reasonCode)
        assertTrue(result.reasonCode.name.startsWith("ALERT_"))
    }

    @Test
    fun unknownReason_onSilent_usesSilentDefault() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(chatResponse(classification("silent", "TOTALLY_BOGUS", confidence = "0.2")))
                .build(),
        )
        val result = provider().classify(request, "sk", "gpt-4.1-mini", null)
        assertEquals(Decision.SILENT, result.decision)
        assertEquals(DecisionReasonCode.SILENT_LOW_CONFIDENCE, result.reasonCode)
    }

    @Test
    fun confidenceAboveOne_clampedToOne_andNotThresholded() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(chatResponse(classification("alert", "ALERT_WORK_INCIDENT", confidence = "1.5")))
                .build(),
        )
        val result = provider().classify(request, "sk", "gpt-4.1-mini", null)
        assertEquals(1.0, result.confidence, 0.0001)
        assertEquals(Decision.ALERT, result.decision) // mapping doesn't apply the 0.80 threshold
    }

    @Test
    fun invalidDecision_throws() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(chatResponse(classification("maybe", "SILENT_ROUTINE")))
                .build(),
        )
        assertThrowsAny { provider().classify(request, "sk", "gpt-4.1-mini", null) }
    }

    @Test
    fun malformedContent_throws() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(chatResponse("{not valid json")).build())
        assertThrowsAny { provider().classify(request, "sk", "gpt-4.1-mini", null) }
    }

    @Test
    fun refusal_throws() = runTest {
        val body = """{"model":"m","choices":[{"message":{"role":"assistant","refusal":"I can't help with that","content":null}}]}"""
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
        assertThrowsAny { provider().classify(request, "sk", "gpt-4.1-mini", null) }
    }

    @Test
    fun http401_throws() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"bad key"}}""").build())
        assertThrowsAny { provider().classify(request, "sk-bad", "gpt-4.1-mini", null) }
    }

    @Test
    fun verifyKey_ok200_isValid_andHitsModelsWithBearer() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"object":"list","data":[]}""").build())
        val result = provider().verifyKey("sk-test")
        assertTrue(result is OpenAiProvider.KeyCheck.Valid)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/models", recorded.target)
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
    }

    @Test
    fun verifyKey_401_isInvalid() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{}}""").build())
        val result = provider().verifyKey("sk-bad")
        assertTrue(result is OpenAiProvider.KeyCheck.Invalid)
        assertEquals("HTTP 401", (result as OpenAiProvider.KeyCheck.Invalid).reason)
    }
}
