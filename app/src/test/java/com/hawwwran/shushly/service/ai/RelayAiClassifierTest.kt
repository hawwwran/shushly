package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.model.AiConnectionState
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class RelayAiClassifierTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private val request = ClassificationRequest(
        packageName = "com.example.app",
        appLabel = "Example",
        title = "Server down",
        body = "Prod is on fire",
        category = "msg",
        postedAt = Instant.parse("2026-06-27T12:00:00Z"),
    )

    private fun classifier(
        baseUrl: String?,
        token: String?,
        instruction: String? = null,
    ): RelayAiClassifier {
        val settings = FakeSettingsRepository(
            AppSettings(
                aiConnection = AiConnectionState(relayBaseUrl = baseUrl),
                customAiInstruction = instruction,
            ),
        )
        return RelayAiClassifier(client, json, settings, FakeDeviceTokenStore(token))
    }

    private suspend fun assertThrows(block: suspend () -> Unit) {
        var threw = false
        try {
            block()
        } catch (_: Throwable) {
            threw = true
        }
        assertTrue("expected an exception to propagate", threw)
    }

    @Test
    fun alert200_mapsResult_andSendsAuthPathAndInstruction() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"schema_version":1,"event_id":"e","decision":"alert","confidence":0.92,""" +
                    """"reason_code":"ALERT_WORK_INCIDENT","user_visible_reason":"Outage.",""" +
                    """"model":"gpt-4.1-mini","latency_ms":312}""",
            ).build(),
        )

        val result = classifier(
            baseUrl = server.url("/").toString(),
            token = "tok-secret",
            instruction = "I'm an on-call SRE; treat pager/PagerDuty/outage as critical",
        ).classify(request)

        assertEquals(Decision.ALERT, result.decision)
        assertEquals(DecisionReasonCode.ALERT_WORK_INCIDENT, result.reasonCode)
        assertEquals(0.92, result.confidence, 0.0001)
        assertEquals("gpt-4.1-mini", result.modelName)
        assertEquals(312L, result.latencyMs)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/classify-notification", recorded.target)
        assertEquals("Bearer tok-secret", recorded.headers["Authorization"])
        val sentBody = recorded.body?.utf8().orEmpty()
        assertTrue(sentBody.contains("\"package_name\""))
        assertTrue(sentBody.contains("\"event_id\""))
        assertTrue(sentBody.contains("\"user_instruction\""))
        assertTrue(sentBody.contains("PagerDuty"))
    }

    @Test
    fun noCustomInstruction_omitsUserInstructionInBody() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"decision":"silent","confidence":0.1,"reason_code":"SILENT_ROUTINE"}""")
                .build(),
        )
        val result = classifier(server.url("/").toString(), "tok").classify(request)
        assertEquals(Decision.SILENT, result.decision)

        val recorded = server.takeRequest()
        assertFalse(recorded.body?.utf8().orEmpty().contains("user_instruction"))
    }

    @Test
    fun http401_throws() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
        assertThrows { classifier(server.url("/").toString(), "tok").classify(request) }
    }

    @Test
    fun http500_throws() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("""{"error":"upstream_error"}""").build())
        assertThrows { classifier(server.url("/").toString(), "tok").classify(request) }
    }

    @Test
    fun malformedJson_throws() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("{not valid json").build())
        assertThrows { classifier(server.url("/").toString(), "tok").classify(request) }
    }

    @Test
    fun blankBaseUrl_throws_withoutHittingNetwork() = runTest {
        assertThrows { classifier(baseUrl = "  ", token = "tok").classify(request) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun nullToken_throws_withoutHittingNetwork() = runTest {
        assertThrows { classifier(baseUrl = server.url("/").toString(), token = null).classify(request) }
        assertEquals(0, server.requestCount)
    }
}
