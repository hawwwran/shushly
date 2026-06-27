package com.hawwwran.shushly.feature.aiconnection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawwwran.shushly.core.data.ApiKeyStore
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.service.ai.OpenAiProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the AI-connection screen. The custom instruction persists immediately on change; the
 * OpenAI API key is a credential and persists only on Test/Save, via [ApiKeyStore]. Verified status
 * is cleared whenever the key changes (spec §10.3). Test validates the key against OpenAI directly.
 */
@HiltViewModel
class AiConnectionViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val openAiProvider: OpenAiProvider,
) : ViewModel() {

    sealed interface TestStatus {
        data object Idle : TestStatus
        data object Testing : TestStatus
        data class Success(val model: String?) : TestStatus
        data class Failure(val reason: String) : TestStatus
    }

    data class UiState(
        val loaded: Boolean = false,
        val apiKey: String = "",
        val showKey: Boolean = false,
        val customInstruction: String = "",
        val model: String = "",
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
            val key = runCatching { apiKeyStore.get() }.getOrNull().orEmpty()
            ui = ui.copy(
                loaded = true,
                apiKey = key,
                customInstruction = s.customAiInstruction.orEmpty(),
                model = s.aiConnection.model,
                isVerified = s.aiConnection.isVerified,
                lastVerifiedAtMs = s.aiConnection.lastVerifiedAtMs,
            )
        }
    }

    fun onKeyChange(value: String) {
        // Persisted on Test/Save, not per keystroke — but a change invalidates a prior verification.
        val wasVerified = ui.isVerified
        ui = ui.copy(apiKey = value, isVerified = false, testStatus = TestStatus.Idle)
        if (wasVerified) viewModelScope.launch { settings.setAiVerified(false, null) }
    }

    fun onInstructionChange(value: String) {
        ui = ui.copy(customInstruction = value)
        viewModelScope.launch { settings.setCustomAiInstruction(value.trim().ifBlank { null }) }
    }

    fun toggleShowKey() {
        ui = ui.copy(showKey = !ui.showKey)
    }

    /** Persist the key without testing. */
    fun save() {
        val key = ui.apiKey.trim()
        viewModelScope.launch { apiKeyStore.set(key.ifBlank { null }) }
    }

    fun test() {
        val key = ui.apiKey.trim()
        if (key.isBlank()) {
            ui = ui.copy(testStatus = TestStatus.Failure("Enter your OpenAI API key first."))
            return
        }
        ui = ui.copy(testStatus = TestStatus.Testing)
        viewModelScope.launch {
            apiKeyStore.set(key) // Test commits the credential.
            when (val result = openAiProvider.verifyKey(key)) {
                is OpenAiProvider.KeyCheck.Valid -> {
                    val now = System.currentTimeMillis()
                    settings.setAiVerified(true, now)
                    // A reachable key clears any stale "AI unavailable" banner (also cleared on a
                    // successful classify); see AiHealthTracker.
                    settings.setAiUnavailableSince(null)
                    ui = ui.copy(
                        testStatus = TestStatus.Success(ui.model),
                        isVerified = true,
                        lastVerifiedAtMs = now,
                    )
                }
                is OpenAiProvider.KeyCheck.Invalid -> {
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
