package com.hawwwran.shushly.service.ai

import com.hawwwran.shushly.core.data.ApiKeyStore
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

    private val directResult = ClassificationResult(
        decision = Decision.ALERT,
        confidence = 0.99,
        reasonCode = DecisionReasonCode.ALERT_WORK_INCIDENT,
        userVisibleReason = "direct",
        modelName = "gpt-4.1-mini",
        latencyMs = 1,
    )
    private val fakeResult = ClassificationResult(
        decision = Decision.SILENT,
        confidence = 0.5,
        reasonCode = DecisionReasonCode.SILENT_ROUTINE,
        userVisibleReason = "fake",
        modelName = "fake",
        latencyMs = 0,
    )

    private class StubDirect(private val result: ClassificationResult) :
        DirectAiClassifier(FakeSettingsRepository(), FakeApiKeyStore(), OpenAiProvider(OkHttpClient(), Json {}, "https://api.openai.com"), FakeAppLearningRepository()) {
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

    private fun verified() = FakeSettingsRepository(
        AppSettings(aiConnection = AiConnectionState(isVerified = true)),
    )

    @Test
    fun keySetAndVerified_routesToDirect() = runTest {
        val direct = StubDirect(directResult)
        val fake = StubFake(fakeResult)
        val router = RoutingAiClassifier(fake, direct, verified(), FakeApiKeyStore("sk-test"))

        val result = router.classify(request)

        assertTrue(direct.called)
        assertFalse(fake.called)
        assertEquals(Decision.ALERT, result.decision)
        assertEquals("gpt-4.1-mini", result.modelName)
    }

    @Test
    fun keySetButNotVerified_debugUsesFake() = runTest {
        val direct = StubDirect(directResult)
        val fake = StubFake(fakeResult)
        // isVerified = false (default) even though a key is present.
        val router = RoutingAiClassifier(fake, direct, FakeSettingsRepository(), FakeApiKeyStore("sk-test"))

        val result = router.classify(request)

        assertFalse(direct.called)
        assertTrue(fake.called)
        assertEquals(Decision.SILENT, result.decision)
    }

    @Test
    fun verifiedButNoKey_debugUsesFake() = runTest {
        val direct = StubDirect(directResult)
        val fake = StubFake(fakeResult)
        val router = RoutingAiClassifier(fake, direct, verified(), FakeApiKeyStore(null))

        val result = router.classify(request)

        assertFalse(direct.called)
        assertTrue(fake.called)
    }

    @Test
    fun notVerified_neverReadsKeyStore_routesToFake() = runTest {
        // A key store that fails (e.g. Keystore init error) must not be touched when not verified —
        // the debug fake path stays intact.
        val throwingStore = object : ApiKeyStore {
            override suspend fun get(): String? = throw IllegalStateException("keystore unavailable")
            override suspend fun set(key: String?) {}
        }
        val direct = StubDirect(directResult)
        val fake = StubFake(fakeResult)
        val router = RoutingAiClassifier(fake, direct, FakeSettingsRepository(), throwingStore)

        val result = router.classify(request) // must not throw

        assertFalse(direct.called)
        assertTrue(fake.called)
        assertEquals(Decision.SILENT, result.decision)
    }
}
