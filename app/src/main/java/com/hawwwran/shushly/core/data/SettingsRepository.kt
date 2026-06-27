package com.hawwwran.shushly.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hawwwran.shushly.core.model.AiConnectionState
import com.hawwwran.shushly.core.model.AiProviderType
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.EligibilityMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "shushly_settings")

/**
 * User settings. Holds no credentials (the API key uses Keystore-backed storage; see [ApiKeyStore]).
 * An interface so the decision units can be unit-tested with a fake.
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun snapshot(): AppSettings
    suspend fun setSmartQuietMode(enabled: Boolean)
    suspend fun setActiveWhenLocked(on: Boolean)
    suspend fun setVibrate(enabled: Boolean)
    suspend fun setAlertSound(uri: String?)
    suspend fun setSimulationMode(enabled: Boolean)
    suspend fun setEligibilityMode(mode: EligibilityMode)
    suspend fun setSelectedPackages(packages: Set<String>)
    suspend fun setAlwaysAlertPackages(packages: Set<String>)
    suspend fun setZenRuleId(id: String?)
    suspend fun setOnboardingComplete(complete: Boolean)
    suspend fun setAiProvider(provider: AiProviderType)
    suspend fun setAiModel(model: String)
    suspend fun setAiVerified(verified: Boolean, atMs: Long?)
    suspend fun setCustomAiInstruction(text: String?)
    suspend fun setAiUnavailableSince(ms: Long?)
    suspend fun setListenerConnectedSince(atMs: Long?)
}

/** DataStore-backed [SettingsRepository]. */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {
    private object Keys {
        val SMART_QUIET = booleanPreferencesKey("smart_quiet_enabled")
        val ACTIVE_WHEN_LOCKED = booleanPreferencesKey("active_when_locked")
        val VIBRATE = booleanPreferencesKey("vibrate_critical")
        val ALERT_SOUND_URI = stringPreferencesKey("alert_sound_uri")
        val SIMULATION = booleanPreferencesKey("simulation_enabled")
        val ELIGIBILITY_MODE = stringPreferencesKey("eligibility_mode")
        val SELECTED = stringSetPreferencesKey("selected_packages")
        val ALWAYS_ALERT = stringSetPreferencesKey("always_alert_packages")
        val ZEN_RULE_ID = stringPreferencesKey("zen_rule_id")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_VERIFIED = booleanPreferencesKey("ai_verified")
        val AI_VERIFIED_AT = longPreferencesKey("ai_verified_at_ms")
        val CUSTOM_AI_INSTRUCTION = stringPreferencesKey("custom_ai_instruction")
        val AI_UNAVAILABLE_SINCE = longPreferencesKey("ai_unavailable_since_ms")
        val LISTENER_CONNECTED_SINCE = longPreferencesKey("listener_connected_since_ms")
    }

    override val settings: Flow<AppSettings> = context.dataStore.data.map { it.toAppSettings() }

    override suspend fun snapshot(): AppSettings = settings.first()

    override suspend fun setSmartQuietMode(enabled: Boolean) =
        edit { it[Keys.SMART_QUIET] = enabled }

    override suspend fun setActiveWhenLocked(on: Boolean) =
        edit { it[Keys.ACTIVE_WHEN_LOCKED] = on }

    override suspend fun setVibrate(enabled: Boolean) =
        edit { it[Keys.VIBRATE] = enabled }

    override suspend fun setAlertSound(uri: String?) =
        edit { prefs -> if (uri == null) prefs.remove(Keys.ALERT_SOUND_URI) else prefs[Keys.ALERT_SOUND_URI] = uri }

    override suspend fun setSimulationMode(enabled: Boolean) =
        edit { it[Keys.SIMULATION] = enabled }

    override suspend fun setEligibilityMode(mode: EligibilityMode) =
        edit { it[Keys.ELIGIBILITY_MODE] = mode.name }

    override suspend fun setSelectedPackages(packages: Set<String>) =
        edit { it[Keys.SELECTED] = packages }

    override suspend fun setAlwaysAlertPackages(packages: Set<String>) =
        edit { it[Keys.ALWAYS_ALERT] = packages }

    override suspend fun setZenRuleId(id: String?) =
        edit { prefs -> if (id == null) prefs.remove(Keys.ZEN_RULE_ID) else prefs[Keys.ZEN_RULE_ID] = id }

    override suspend fun setOnboardingComplete(complete: Boolean) =
        edit { it[Keys.ONBOARDING_COMPLETE] = complete }

    override suspend fun setAiProvider(provider: AiProviderType) =
        edit { it[Keys.AI_PROVIDER] = provider.name }

    override suspend fun setAiModel(model: String) =
        edit { it[Keys.AI_MODEL] = model }

    override suspend fun setAiVerified(verified: Boolean, atMs: Long?) =
        edit { prefs ->
            prefs[Keys.AI_VERIFIED] = verified
            if (atMs == null) prefs.remove(Keys.AI_VERIFIED_AT) else prefs[Keys.AI_VERIFIED_AT] = atMs
        }

    override suspend fun setCustomAiInstruction(text: String?) =
        edit { prefs ->
            if (text == null) prefs.remove(Keys.CUSTOM_AI_INSTRUCTION) else prefs[Keys.CUSTOM_AI_INSTRUCTION] = text
        }

    override suspend fun setAiUnavailableSince(ms: Long?) =
        edit { prefs -> if (ms == null) prefs.remove(Keys.AI_UNAVAILABLE_SINCE) else prefs[Keys.AI_UNAVAILABLE_SINCE] = ms }

    override suspend fun setListenerConnectedSince(atMs: Long?) =
        edit { prefs -> if (atMs == null) prefs.remove(Keys.LISTENER_CONNECTED_SINCE) else prefs[Keys.LISTENER_CONNECTED_SINCE] = atMs }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        smartQuietModeEnabled = this[Keys.SMART_QUIET] ?: false,
        activeWhenLocked = this[Keys.ACTIVE_WHEN_LOCKED] ?: true,
        vibrateForCriticalAlerts = this[Keys.VIBRATE] ?: true,
        alertSoundUri = this[Keys.ALERT_SOUND_URI],
        simulationModeEnabled = this[Keys.SIMULATION] ?: false,
        eligibilityMode = this[Keys.ELIGIBILITY_MODE]
            ?.let { runCatching { EligibilityMode.valueOf(it) }.getOrNull() }
            ?: EligibilityMode.ALL_APPS_EXCEPT_SELECTED,
        selectedPackages = this[Keys.SELECTED] ?: emptySet(),
        alwaysAlertPackages = this[Keys.ALWAYS_ALERT] ?: emptySet(),
        zenRuleId = this[Keys.ZEN_RULE_ID],
        onboardingComplete = this[Keys.ONBOARDING_COMPLETE] ?: false,
        aiConnection = AiConnectionState(
            provider = this[Keys.AI_PROVIDER]
                ?.let { runCatching { AiProviderType.valueOf(it) }.getOrNull() }
                ?: AiProviderType.OPENAI,
            model = this[Keys.AI_MODEL] ?: AiConnectionState.DEFAULT_MODEL,
            isVerified = this[Keys.AI_VERIFIED] ?: false,
            lastVerifiedAtMs = this[Keys.AI_VERIFIED_AT],
        ),
        customAiInstruction = this[Keys.CUSTOM_AI_INSTRUCTION],
        aiUnavailableSince = this[Keys.AI_UNAVAILABLE_SINCE],
        listenerConnectedSinceMs = this[Keys.LISTENER_CONNECTED_SINCE],
    )
}
