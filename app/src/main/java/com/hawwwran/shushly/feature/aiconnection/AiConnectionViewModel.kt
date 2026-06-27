package com.hawwwran.shushly.feature.aiconnection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawwwran.shushly.core.data.DeviceTokenStore
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.AiConnectionMode
import com.hawwwran.shushly.service.ai.RelayConnectionTester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the AI-connection screen. URL / mode / custom instruction persist immediately on change
 * (like the app picker); the device token is a credential and persists only on Test/Save, via
 * [DeviceTokenStore]. Verified status is cleared whenever the URL or token changes (spec §10.3).
 */
@HiltViewModel
class AiConnectionViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val deviceTokenStore: DeviceTokenStore,
    private val tester: RelayConnectionTester,
) : ViewModel() {

    /** Cosmetic preset: both map to RELAY_BACKEND; only the label/help text differs (spec §13.4). */
    enum class RelayPreset { RECOMMENDED, SELF_HOSTED }

    sealed interface TestStatus {
        data object Idle : TestStatus
        data object Testing : TestStatus
        data class Success(val model: String?) : TestStatus
        data class Failure(val reason: String) : TestStatus
    }

    data class UiState(
        val loaded: Boolean = false,
        val preset: RelayPreset = RelayPreset.RECOMMENDED,
        val relayUrl: String = "",
        val token: String = "",
        val showToken: Boolean = false,
        val customInstruction: String = "",
        val isVerified: Boolean = false,
        val lastVerifiedAtMs: Long? = null,
        val testStatus: TestStatus = TestStatus.Idle,
    )

    var ui by mutableStateOf(UiState())
        private set

    init {
        viewModelScope.launch {
            val s = settings.snapshot()
            // A Keystore/EncryptedSharedPreferences failure must not crash the screen.
            val token = runCatching { deviceTokenStore.get() }.getOrNull().orEmpty()
            ui = ui.copy(
                loaded = true,
                relayUrl = s.aiConnection.relayBaseUrl.orEmpty(),
                token = token,
                customInstruction = s.customAiInstruction.orEmpty(),
                isVerified = s.aiConnection.isVerified,
                lastVerifiedAtMs = s.aiConnection.lastVerifiedAtMs,
            )
        }
    }

    fun onPresetChange(preset: RelayPreset) {
        ui = ui.copy(preset = preset)
        // Both presets are relay-backend; persist the (only) supported mode.
        viewModelScope.launch { settings.setAiConnectionMode(AiConnectionMode.RELAY_BACKEND) }
    }

    fun onUrlChange(value: String) {
        val wasVerified = ui.isVerified
        ui = ui.copy(relayUrl = value, isVerified = false, testStatus = TestStatus.Idle)
        viewModelScope.launch {
            settings.setRelayBaseUrl(value.trim().ifBlank { null })
            if (wasVerified) settings.setAiVerified(false, null)
        }
    }

    fun onTokenChange(value: String) {
        // Persisted on Test/Save, not per keystroke — but a change invalidates a prior verification.
        val wasVerified = ui.isVerified
        ui = ui.copy(token = value, isVerified = false, testStatus = TestStatus.Idle)
        if (wasVerified) viewModelScope.launch { settings.setAiVerified(false, null) }
    }

    fun onInstructionChange(value: String) {
        ui = ui.copy(customInstruction = value)
        viewModelScope.launch { settings.setCustomAiInstruction(value.trim().ifBlank { null }) }
    }

    fun toggleShowToken() {
        ui = ui.copy(showToken = !ui.showToken)
    }

    /** Debug-only convenience: fill the fields with the dev relay values. User still taps Test/Save. */
    fun fillDevRelay(url: String, token: String) {
        val wasVerified = ui.isVerified
        ui = ui.copy(relayUrl = url, token = token, isVerified = false, testStatus = TestStatus.Idle)
        viewModelScope.launch {
            settings.setRelayBaseUrl(url.trim().ifBlank { null })
            if (wasVerified) settings.setAiVerified(false, null)
        }
    }

    /** Persist the credential (and trimmed URL) without testing. */
    fun save() {
        val url = ui.relayUrl.trim()
        val token = ui.token.trim()
        viewModelScope.launch {
            settings.setRelayBaseUrl(url.ifBlank { null })
            deviceTokenStore.set(token.ifBlank { null })
        }
    }

    fun test() {
        val url = ui.relayUrl.trim()
        val token = ui.token.trim()
        if (url.isBlank() || token.isBlank()) {
            ui = ui.copy(testStatus = TestStatus.Failure("Enter a relay URL and device token first."))
            return
        }
        ui = ui.copy(testStatus = TestStatus.Testing)
        viewModelScope.launch {
            // Test commits the credential + trimmed URL.
            settings.setRelayBaseUrl(url)
            deviceTokenStore.set(token)
            when (val result = tester.check(url, token)) {
                is RelayConnectionTester.Result.Success -> {
                    val now = System.currentTimeMillis()
                    settings.setAiVerified(true, now)
                    ui = ui.copy(
                        testStatus = TestStatus.Success(result.model),
                        isVerified = true,
                        lastVerifiedAtMs = now,
                    )
                }
                is RelayConnectionTester.Result.Failure -> {
                    settings.setAiVerified(false, null)
                    ui = ui.copy(
                        testStatus = TestStatus.Failure(result.reason),
                        isVerified = false,
                        lastVerifiedAtMs = null,
                    )
                }
            }
        }
    }
}
