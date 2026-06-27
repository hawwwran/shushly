package com.hawwwran.shushly.feature.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.core.model.EligibilityMode
import com.hawwwran.shushly.readiness.ReadinessChecker
import com.hawwwran.shushly.service.alerting.CriticalAlertSounder
import com.hawwwran.shushly.service.alerting.NotificationChannels
import com.hawwwran.shushly.service.listener.NotificationPipeline
import com.hawwwran.shushly.service.quietmode.QuietModeController
import com.hawwwran.shushly.service.quietmode.QuietModeState
import com.hawwwran.shushly.service.quietmode.SmartQuietModeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReadinessUi(
    val listenerEnabled: Boolean = false,
    val policyAccessGranted: Boolean = false,
    val postNotificationsGranted: Boolean = false,
    val alarmAudible: Boolean = false,
    val batteryOptimizationExempt: Boolean = true,
    val alarmVolume: Int = 0,
    val alarmVolumeMax: Int = 1,
) {
    // Sound-only minimum: listener + DND-policy access. postNotificationsGranted, alarmAudible, and
    // batteryOptimizationExempt are advisory, not blocking.
    val minimumMet: Boolean
        get() = listenerEnabled && policyAccessGranted
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsRepository,
    private val quietMode: QuietModeController,
    private val readiness: ReadinessChecker,
    private val pipeline: NotificationPipeline,
    private val smartQuietMode: SmartQuietModeManager,
    private val sounder: CriticalAlertSounder,
) : ViewModel() {

    val settingsState: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val quietState: StateFlow<QuietModeState> = quietMode.observeState()

    /** null until the persisted flag is first read, then true/false. Drives the start destination. */
    val onboardingComplete: StateFlow<Boolean?> = settings.settings
        .map { it.onboardingComplete }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var readinessUi by mutableStateOf(ReadinessUi())
        private set

    init {
        refresh()
    }

    fun refresh() {
        NotificationChannels.ensureBaseChannels(appContext)
        quietMode.refresh()
        readinessUi = ReadinessUi(
            listenerEnabled = readiness.listenerEnabled(),
            policyAccessGranted = readiness.policyAccessGranted(),
            postNotificationsGranted = readiness.postNotificationsGranted(),
            alarmAudible = readiness.alarmAudible(),
            batteryOptimizationExempt = readiness.batteryOptimizationExempt(),
            alarmVolume = readiness.alarmVolume(),
            alarmVolumeMax = readiness.alarmVolumeMax(),
        )
    }

    fun setSmartQuietMode(on: Boolean) {
        viewModelScope.launch {
            smartQuietMode.setEnabled(on)
            refresh()
        }
    }

    fun setActiveWhenLocked(on: Boolean) {
        viewModelScope.launch {
            smartQuietMode.setActiveWhenLocked(on)
            refresh()
        }
    }

    fun setVibrate(enabled: Boolean) {
        viewModelScope.launch { settings.setVibrate(enabled) }
    }

    fun setAlertSound(uri: String?) {
        viewModelScope.launch { settings.setAlertSound(uri) }
    }

    /** Sets the device alarm-stream volume directly, then refreshes so the slider reflects it. */
    fun setAlertVolume(level: Int) {
        readiness.setAlarmVolume(level)
        refresh()
    }

    /** Plays the currently-configured alert sound; it rides the alarm stream, so this is the true loudness. */
    fun previewAlertSound() {
        viewModelScope.launch {
            val s = settings.snapshot()
            sounder.playAlert(s.vibrateForCriticalAlerts, s.alertSoundUri)
        }
    }

    fun setEligibilityMode(mode: EligibilityMode) {
        viewModelScope.launch { settings.setEligibilityMode(mode) }
    }

    fun setSimulationMode(enabled: Boolean) {
        viewModelScope.launch { settings.setSimulationMode(enabled) }
    }

    fun completeOnboarding() {
        viewModelScope.launch { settings.setOnboardingComplete(true) }
    }

    fun fireTest(alert: Boolean) {
        val text = if (alert) {
            "Production deployment failed, action needed TEST_ALERT"
        } else {
            "Just saying hi, nothing urgent TEST_SILENT"
        }
        viewModelScope.launch { pipeline.debugFire(text) }
    }
}
