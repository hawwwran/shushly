package com.hawwwran.shushly.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.EligibilityMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "shushly_settings")

/** DataStore-backed user settings. Holds no credentials (those use Keystore in Phase 1). */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val SMART_QUIET = booleanPreferencesKey("smart_quiet_enabled")
        val VIBRATE = booleanPreferencesKey("vibrate_critical")
        val SIMULATION = booleanPreferencesKey("simulation_enabled")
        val ELIGIBILITY_MODE = stringPreferencesKey("eligibility_mode")
        val SELECTED = stringSetPreferencesKey("selected_packages")
        val ZEN_RULE_ID = stringPreferencesKey("zen_rule_id")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toAppSettings() }

    suspend fun snapshot(): AppSettings = settings.first()

    suspend fun setSmartQuietMode(enabled: Boolean) =
        edit { it[Keys.SMART_QUIET] = enabled }

    suspend fun setVibrate(enabled: Boolean) =
        edit { it[Keys.VIBRATE] = enabled }

    suspend fun setSimulationMode(enabled: Boolean) =
        edit { it[Keys.SIMULATION] = enabled }

    suspend fun setEligibilityMode(mode: EligibilityMode) =
        edit { it[Keys.ELIGIBILITY_MODE] = mode.name }

    suspend fun setSelectedPackages(packages: Set<String>) =
        edit { it[Keys.SELECTED] = packages }

    suspend fun setZenRuleId(id: String?) =
        edit { prefs -> if (id == null) prefs.remove(Keys.ZEN_RULE_ID) else prefs[Keys.ZEN_RULE_ID] = id }

    suspend fun setOnboardingComplete(complete: Boolean) =
        edit { it[Keys.ONBOARDING_COMPLETE] = complete }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        smartQuietModeEnabled = this[Keys.SMART_QUIET] ?: false,
        vibrateForCriticalAlerts = this[Keys.VIBRATE] ?: true,
        simulationModeEnabled = this[Keys.SIMULATION] ?: false,
        eligibilityMode = this[Keys.ELIGIBILITY_MODE]
            ?.let { runCatching { EligibilityMode.valueOf(it) }.getOrNull() }
            ?: EligibilityMode.ALL_APPS_EXCEPT_SELECTED,
        selectedPackages = this[Keys.SELECTED] ?: emptySet(),
        zenRuleId = this[Keys.ZEN_RULE_ID],
        onboardingComplete = this[Keys.ONBOARDING_COMPLETE] ?: false,
    )
}
