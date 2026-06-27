@file:OptIn(ExperimentalMaterial3Api::class)

package com.hawwwran.shushly.feature.home

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hawwwran.shushly.core.model.EligibilityMode
import com.hawwwran.shushly.feature.common.OkColor
import com.hawwwran.shushly.feature.common.openAppNotificationSettings
import com.hawwwran.shushly.feature.common.startSafely

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onChooseApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by viewModel.settingsState.collectAsState()
    val readiness = viewModel.readinessUi

    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Shushly") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Quiet by default. Important by exception.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (settings.simulationModeEnabled) {
                SimulationBanner()
            }

            ReadinessCard(
                readiness = readiness,
                aiVerified = settings.aiConnection.isVerified,
                onFixListener = { context.startSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onFixPolicy = { context.startSafely(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                onFixNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.openAppNotificationSettings()
                    }
                },
                onFixAlarm = { context.startSafely(Intent(Settings.ACTION_SOUND_SETTINGS)) },
            )

            MasterToggleCard(
                enabled = readiness.minimumMet,
                smartQuietOn = settings.smartQuietModeEnabled,
                vibrate = settings.vibrateForCriticalAlerts,
                onSmartQuietChange = viewModel::setSmartQuietMode,
                onVibrateChange = viewModel::setVibrate,
            )

            SimulationCard(
                simulationOn = settings.simulationModeEnabled,
                onSimulationChange = viewModel::setSimulationMode,
            )

            EligibilityCard(
                mode = settings.eligibilityMode,
                selectedCount = settings.selectedPackages.size,
                onModeChange = viewModel::setEligibilityMode,
                onChooseApps = onChooseApps,
            )

            DebugCard(
                quietOn = settings.smartQuietModeEnabled,
                simulationOn = settings.simulationModeEnabled,
                onFireAlert = { viewModel.fireTest(alert = true) },
                onFireSilent = { viewModel.fireTest(alert = false) },
            )

            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Text("Decision history")
            }
        }
    }
}

@Composable
private fun ReadinessCard(
    readiness: ReadinessUi,
    aiVerified: Boolean,
    onFixListener: () -> Unit,
    onFixPolicy: () -> Unit,
    onFixNotifications: () -> Unit,
    onFixAlarm: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ReadinessRow("Notification access", readiness.listenerEnabled, onFixListener)
            ReadinessRow("Quiet-mode (DND) access", readiness.policyAccessGranted, onFixPolicy)
            ReadinessRow("Notifications allowed for Shushly", readiness.postNotificationsGranted, onFixNotifications)
            ReadinessRow("Shushly can be heard (alarm volume)", readiness.alarmAudible, onFixAlarm)
            AiConnectionReadinessRow(aiVerified)
        }
    }
}

/**
 * Informational only: AI is optional. Without a verified relay, eligible notifications just stay
 * silent — so this row never blocks setup (it's not part of [ReadinessUi.minimumMet]) and has no Fix.
 */
@Composable
private fun AiConnectionReadinessRow(verified: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (verified) Icons.Filled.CheckCircle else Icons.Filled.Info,
            contentDescription = null,
            tint = if (verified) OkColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("AI connection verified", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (verified) {
                    "Optional. A relay is connected and verified."
                } else {
                    "Optional. Without it, eligible notifications just stay silent."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReadinessRow(label: String, satisfied: Boolean, onFix: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (satisfied) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (satisfied) OkColor else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!satisfied) {
            OutlinedButton(onClick = onFix) { Text("Fix") }
        }
    }
}

@Composable
private fun MasterToggleCard(
    enabled: Boolean,
    smartQuietOn: Boolean,
    vibrate: Boolean,
    onSmartQuietChange: (Boolean) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Smart Quiet Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (enabled) "When on, ordinary notifications stay quiet." else "Complete setup above to enable.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = smartQuietOn, onCheckedChange = onSmartQuietChange, enabled = enabled)
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vibrate for critical alerts", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = vibrate, onCheckedChange = onVibrateChange)
            }
        }
    }
}

@Composable
private fun EligibilityCard(
    mode: EligibilityMode,
    selectedCount: Int,
    onModeChange: (EligibilityMode) -> Unit,
    onChooseApps: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI may re-alert for", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ModeOption(
                label = "Selected apps",
                selected = mode == EligibilityMode.SELECTED_APPS,
                onClick = { onModeChange(EligibilityMode.SELECTED_APPS) },
            )
            ModeOption(
                label = "All apps except selected",
                selected = mode == EligibilityMode.ALL_APPS_EXCEPT_SELECTED,
                onClick = { onModeChange(EligibilityMode.ALL_APPS_EXCEPT_SELECTED) },
            )
            Text(
                text = "$selectedCount ${if (selectedCount == 1) "app" else "apps"} selected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onChooseApps) { Text("Choose apps") }
        }
    }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SimulationBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = "Simulation mode on — important alerts are logged, not sounded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SimulationCard(simulationOn: Boolean, onSimulationChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Simulation mode",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Switch(checked = simulationOn, onCheckedChange = onSimulationChange)
            }
            Text(
                text = "Test what Shushly would do — notifications are classified and logged, but it never makes a sound.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DebugCard(
    quietOn: Boolean,
    simulationOn: Boolean,
    onFireAlert: () -> Unit,
    onFireSilent: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Developer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (quietOn) {
                    "Push a synthesized notification through the real pipeline."
                } else {
                    "Turn on Smart Quiet Mode first; otherwise these are skipped."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (simulationOn) {
                Text(
                    text = "Simulation mode is on — TEST_ALERT is logged as WOULD_ALERT, not sounded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onFireAlert, modifier = Modifier.weight(1f)) { Text("Fire TEST_ALERT") }
                OutlinedButton(onClick = onFireSilent, modifier = Modifier.weight(1f)) { Text("Fire TEST_SILENT") }
            }
        }
    }
}
