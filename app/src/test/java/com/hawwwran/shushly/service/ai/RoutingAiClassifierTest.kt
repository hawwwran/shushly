package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.data.DeviceTokenStore
import com.hawwwran.shushly.core.model.AiConnectionState
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.ClassificationRequest
import com.hawwwran.shushly.core.model.ClassificationResult
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RoutingAiClassifierTest {

    private val request = ClassificationRequest(
        packageName = "com.example.app",
        appLabel = "Example",
        title = "hi",
        body = "there",
        category = null,
        postedAt = Instant.parse("2026-06-27T12:00:00Z"),
    )

    private val alertResult = ClassificationResult(
        decision = Decision.ALERT,
        confidence = 0.99,
        reasonCode = DecisionReasonCode.ALERT_WORK_INCIDENT,
        userVisibleReason = "relay",
        modelName = "relay-model",
        latencyMs = 1,
    )
    private val silentResult = ClassificationResult(
        decision = Decision.SILENT,
        confidence = 0.5,
        reasonCode = DecisionReasonCode.SILENT_ROUTINE,
        userVisibleReason = "fake",
        modelName = "fake",
        latencyMs = 0,
    )

    /** Records which branch was taken without doing real work. */
    private class StubRelay(private val result: ClassificationResult) :
        RelayAiClassifier(OkHttpClient(), Json {}, FakeSettingsRepository(), FakeDeviceTokenStore()) {
        var called = false
        override suspend fun classify(request: ClassificationRequest): ClassificationResult {
            called = true
            return result
        }
    }

    private class StubFake(private val result: ClassificationResult) : FakeAiClassifier() {
        var called = false
        override suspend fun classify(request: ClassificationRequest): ClassificationResult {
            called = true
            return result
        }
    }

    @Test
    fun relayConfigured_routesToRelay() = runTest {
        val relay = StubRelay(alertResult)
        val fake = StubFake(silentResult)
        val settings = FakeSettingsRepository(
            AppSettings(aiConnection = AiConnectionState(relayBaseUrl = "https://relay.example")),
        )
        val router = RoutingAiClassifier(fake, relay, settings, FakeDeviceTokenStore("tok"))

        val result = router.classify(request)

        assertTrue(relay.called)
        assertFalse(fake.called)
        assertEquals(Decision.ALERT, result.decision)
        assertEquals("relay-model", result.modelName)
    }

    @Test
    fun notConfigured_debugUsesFake() = runTest {
        val relay = StubRelay(alertResult)
        val fake = StubFake(silentResult)
        val settings = FakeSettingsRepository(AppSettings()) // no relayBaseUrl
        val router = RoutingAiClassifier(fake, relay, settings, FakeDeviceTokenStore(null))

        val result = router.classify(request)

        assertFalse(relay.called)
        assertTrue(fake.called)
        assertEquals(Decision.SILENT, result.decision)
    }

    @Test
    fun blankBaseUrl_neverReadsTokenStore_routesToFake() = runTest {
        // A token store that fails (e.g. Keystore init error) must not be touched when no relay
        // base URL is configured — the debug fake path stays intact.
        val throwingStore = object : DeviceTokenStore {
            override suspend fun get(): String? = throw IllegalStateException("keystore unavailable")
            override suspend fun set(token: String?) {}
        }
        val relay = StubRelay(alertResult)
        val fake = StubFake(silentResult)
        val settings = FakeSettingsRepository(AppSettings()) // no relayBaseUrl

        val router = RoutingAiClassifier(fake, relay, settings, throwingStore)
        val result = router.classify(request) // must not throw

        assertFalse(relay.called)
        assertTrue(fake.called)
        assertEquals(Decision.SILENT, result.decision)
    }

    @Test
    fun baseUrlSetButTokenBlank_routesToFake() = runTest {
        val relay = StubRelay(alertResult)
        val fake = StubFake(silentResult)
        val settings = FakeSettingsRepository(
            AppSettings(aiConnection = AiConnectionState(relayBaseUrl = "https://relay.example")),
        )
        val router = RoutingAiClassifier(fake, relay, settings, FakeDeviceTokenStore("   "))

        router.classify(request)

        assertFalse(relay.called)
        assertTrue(fake.called)
    }
}
