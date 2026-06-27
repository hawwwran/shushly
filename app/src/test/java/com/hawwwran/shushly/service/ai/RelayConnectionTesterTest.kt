package com.hawwwran.shushly.service.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelayConnectionTesterTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val tester = RelayConnectionTester(OkHttpClient(), json)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.close() } // a test may have already closed it to simulate a network error
    }

    @Test
    fun ok200_returnsSuccessWithModel_andHitsAuthCheckWithBearer() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"ok":true,"model":"gpt-4.1-mini"}""").build(),
        )

        val result = tester.check(server.url("/").toString(), "tok-123")

        assertTrue(result is RelayConnectionTester.Result.Success)
        assertEquals("gpt-4.1-mini", (result as RelayConnectionTester.Result.Success).model)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/auth-check", recorded.target)
        assertEquals("Bearer tok-123", recorded.headers["Authorization"])
    }

    @Test
    fun http401_returnsFailureWithStatus() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())

        val result = tester.check(server.url("/").toString(), "bad-token")

        assertTrue(result is RelayConnectionTester.Result.Failure)
        assertEquals("HTTP 401", (result as RelayConnectionTester.Result.Failure).reason)
    }

    @Test
    fun networkError_returnsFailure() = runTest {
        val url = server.url("/").toString()
        server.close() // nothing is listening now -> connection refused

        val result = tester.check(url, "tok")

        assertTrue(result is RelayConnectionTester.Result.Failure)
    }
}
