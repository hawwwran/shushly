package com.hawwwran.shushly.feature.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawwwran.shushly.core.data.SettingsRepository
import com.hawwwran.shushly.core.model.AppSettings
import com.hawwwran.shushly.readiness.ReadinessChecker
import com.hawwwran.shushly.service.alerting.NotificationChannels
import com.hawwwran.shushly.service.listener.DecisionLogEntry
import com.hawwwran.shushly.service.listener.NotificationPipeline
import com.hawwwran.shushly.service.quietmode.QuietModeController
import com.hawwwran.shushly.service.quietmode.QuietModeResult
import com.hawwwran.shushly.service.quietmode.QuietModeState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReadinessUi(
    val listenerEnabled: Boolean = false,
    val policyAccessGranted: Boolean = false,
    val postNotificationsGranted: Boolean = false,
    val criticalChannelCanAlert: Boolean = false,
) {
    val minimumMet: Boolean
        get() = listenerEnabled && policyAccessGranted && postNotificationsGranted && criticalChannelCanAlert
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsRepository,
    private val quietMode: QuietModeController,
    private val readiness: ReadinessChecker,
    private val pipeline: NotificationPipeline,
) : ViewModel() {

    val settingsState: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val quietState: StateFlow<QuietModeState> = quietMode.observeState()

    val decisionLog: StateFlow<List<DecisionLogEntry>> = pipeline.log

    var readinessUi by mutableStateOf(ReadinessUi())
        private set

    init {
        refresh()
    }

    fun refresh() {
        NotificationChannels.ensureAll(appContext)
        quietMode.refresh()
        readinessUi = ReadinessUi(
            listenerEnabled = readiness.listenerEnabled(),
            policyAccessGranted = readiness.policyAccessGranted(),
            postNotificationsGranted = readiness.postNotificationsGranted(),
            criticalChannelCanAlert = readiness.criticalChannelCanAlert(),
        )
    }

    fun setSmartQuietMode(on: Boolean) {
        viewModelScope.launch {
            if (on) {
                when (quietMode.enable()) {
                    is QuietModeResult.Success -> settings.setSmartQuietMode(true)
                    is QuietModeResult.Unavailable -> settings.setSmartQuietMode(false)
                }
            } else {
                quietMode.disable()
                settings.setSmartQuietMode(false)
            }
            refresh()
        }
    }

    fun setVibrate(enabled: Boolean) {
        viewModelScope.launch { settings.setVibrate(enabled) }
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
