@file:OptIn(ExperimentalMaterial3Api::class)

package com.hawwwran.shushly.feature.home

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import com.hawwwran.shushly.core.model.EligibilityMode
import com.hawwwran.shushly.feature.common.OkColor
import com.hawwwran.shushly.feature.picker.PickerTarget
import com.hawwwran.shushly.feature.common.openAppNotificationSettings
import com.hawwwran.shushly.feature.common.openBatteryOptimizationSettings
import com.hawwwran.shushly.feature.common.startSafely

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onChooseApps: (PickerTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by viewModel.settingsState.collectAsState()
    val readiness = viewModel.readinessUi

    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    // Live-sync the alert-volume slider with the system alarm volume while Home is visible: any
    // external volume change (system panel / rocker) refreshes the readiness state, which re-reads
    // alarmVolume so the slider's remember(alarmVolume) re-keys and the thumb follows. The write path
    // (slider → setStreamVolume) is unchanged.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                viewModel.refresh()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

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

            val aiUnavailableSince = settings.aiUnavailableSince
            if (aiUnavailableSince != null && settings.smartQuietModeEnabled && settings.aiConnection.isVerified) {
                AiUnavailableBanner(sinceMs = aiUnavailableSince)
            }

            ReadinessCard(
                readiness = readiness,
                listenerConnected = settings.listenerConnectedSinceMs != null,
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
                onFixBattery = { context.openBatteryOptimizationSettings() },
            )

            MasterToggleCard(
                enabled = readiness.minimumMet,
                smartQuietOn = settings.smartQuietModeEnabled,
                activeWhenLocked = settings.activeWhenLocked,
                vibrate = settings.vibrateForCriticalAlerts,
                alarmVolume = readiness.alarmVolume,
                alarmVolumeMax = readiness.alarmVolumeMax,
                onSmartQuietChange = viewModel::setSmartQuietMode,
                onActiveWhenLockedChange = viewModel::setActiveWhenLocked,
                onVibrateChange = viewModel::setVibrate,
                onAlarmVolumeChange = viewModel::setAlertVolume,
            )

            AlertSoundCard(
                currentUri = settings.alertSoundUri,
                onPick = viewModel::setAlertSound,
                onPreview = viewModel::previewAlertSound,
            )

            SimulationCard(
                simulationOn = settings.simulationModeEnabled,
                onSimulationChange = viewModel::setSimulationMode,
            )

            EligibilityCard(
                mode = settings.eligibilityMode,
                selectedCount = settings.selectedPackages.size,
                onModeChange = viewModel::setEligibilityMode,
                onChooseApps = { onChooseApps(PickerTarget.ELIGIBILITY) },
            )

            AlwaysAlertCard(
                count = settings.alwaysAlertPackages.size,
                onChooseApps = { onChooseApps(PickerTarget.ALWAYS_ALERT) },
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
    listenerConnected: Boolean,
    aiVerified: Boolean,
    onFixListener: () -> Unit,
    onFixPolicy: () -> Unit,
    onFixNotifications: () -> Unit,
    onFixAlarm: () -> Unit,
    onFixBattery: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ReadinessRow("Notification access", readiness.listenerEnabled, onFixListener)
            ListenerRunningRow(connected = listenerConnected, permissionGranted = readiness.listenerEnabled)
            ReadinessRow("Quiet-mode (DND) access", readiness.policyAccessGranted, onFixPolicy)
            ReadinessRow("Notifications allowed for Shushly", readiness.postNotificationsGranted, onFixNotifications)
            ReadinessRow("Shushly can be heard (alarm volume)", readiness.alarmAudible, onFixAlarm)
            ReadinessRow("Battery optimization exempt", readiness.batteryOptimizationExempt, onFixBattery)
            AiConnectionReadinessRow(aiVerified)
        }
    }
}

/**
 * Whether the listener is actually bound now (distinct from the permission row). FunTouch can kill
 * the binding silently; granted-but-not-connected is shown as a problem with a recovery hint. No Fix
 * button — recovery is reopening the app and/or the battery-optimization row above.
 */
@Composable
private fun ListenerRunningRow(connected: Boolean, permissionGranted: Boolean) {
    val (icon, tint) = when {
        connected -> Icons.Filled.CheckCircle to OkColor
        permissionGranted -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
        else -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val hint = when {
        connected -> "Connected and receiving notifications."
        permissionGranted -> "Not running. Reopen Shushly; if it keeps dropping, exempt it from battery optimization."
        else -> "Starts once notification access is granted."
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Notification listener running", style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** AI is down (errors recorded), so eligible notifications stay silent until it recovers (§13.2). */
@Composable
private fun AiUnavailableBanner(sinceMs: Long) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "AI unavailable — eligible notifications are staying silent.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                val rel = DateUtils.getRelativeTimeSpanString(
                    sinceMs,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString()
                Text(
                    text = "Since $rel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
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
    activeWhenLocked: Boolean,
    vibrate: Boolean,
    alarmVolume: Int,
    alarmVolumeMax: Int,
    onSmartQuietChange: (Boolean) -> Unit,
    onActiveWhenLockedChange: (Boolean) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onAlarmVolumeChange: (Int) -> Unit,
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("Active when locked", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "When on, Shushly only steps in while your phone is locked. If you're using the phone, notifications behave normally.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = activeWhenLocked, onCheckedChange = onActiveWhenLockedChange)
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vibrate for critical alerts", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = vibrate, onCheckedChange = onVibrateChange)
            }
            HorizontalDivider()
            // Slider snaps to the device's discrete alarm levels (0..max). Local state during drag;
            // persist on release. Re-syncs when the live alarm volume changes (keyed remember).
            val max = alarmVolumeMax.coerceAtLeast(1)
            var level by remember(alarmVolume) { mutableStateOf(alarmVolume.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alert volume", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${level.roundToInt() * 100 / max}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = level,
                onValueChange = { level = it },
                onValueChangeFinished = { onAlarmVolumeChange(level.roundToInt()) },
                valueRange = 0f..max.toFloat(),
                steps = (max - 1).coerceAtLeast(0),
            )
            Text(
                text = "This is your phone's alarm volume — your alarms and timers use it too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlertSoundCard(currentUri: String?, onPick: (String?) -> Unit, onPreview: () -> Unit) {
    val context = LocalContext.current
    val title = remember(currentUri) { alertSoundTitle(context, currentUri) }
    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val picked: Uri? = result.data?.let { data ->
                if (Build.VERSION.SDK_INT >= 33) {
                    data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
            }
            val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            // A null pick (or the system default) means "use the default" → store null.
            onPick(if (picked == null || picked == defaultUri) null else picked.toString())
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Alert sound", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { pickerLauncher.launch(ringtonePickerIntent(currentUri)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Choose sound")
                }
                OutlinedButton(onClick = onPreview, modifier = Modifier.weight(1f)) {
                    Text("Play")
                }
            }
            Text(
                text = "Plays at your device's alarm volume, so it's heard even in silent / Do Not Disturb.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun alertSoundTitle(context: Context, uri: String?): String {
    if (uri == null) return "Default"
    val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return "Default"
    return runCatching { RingtoneManager.getRingtone(context, parsed)?.getTitle(context) }.getOrNull() ?: "Default"
}

private fun ringtonePickerIntent(currentUri: String?): Intent {
    val existing: Uri? = currentUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Shushly alert sound")
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
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
            Text(
                text = "Notifications stay silent under Smart Quiet Mode; this picks which apps the AI may sound an alert for. " +
                    "Always-alert apps (below) are handled separately and sound first, without the AI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModeOption(
                label = "Selected apps",
                supporting = "Only the apps you pick can sound an alert. All others stay silent.",
                selected = mode == EligibilityMode.SELECTED_APPS,
                onClick = { onModeChange(EligibilityMode.SELECTED_APPS) },
            )
            ModeOption(
                label = "All apps except selected",
                supporting = "Every app can sound an alert except the ones you pick.",
                selected = mode == EligibilityMode.ALL_APPS_EXCEPT_SELECTED,
                onClick = { onModeChange(EligibilityMode.ALL_APPS_EXCEPT_SELECTED) },
            )
            Text(
                text = nowEligibilitySummary(mode, selectedCount),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            OutlinedButton(onClick = onChooseApps) { Text("Choose apps") }
        }
    }
}

private fun nowEligibilitySummary(mode: EligibilityMode, count: Int): String {
    val apps = if (count == 1) "app" else "apps"
    return when (mode) {
        EligibilityMode.SELECTED_APPS ->
            if (count == 0) "Now: no apps can sound — pick some below." else "Now: $count $apps can sound an alert."
        EligibilityMode.ALL_APPS_EXCEPT_SELECTED ->
            if (count == 0) "Now: every app can sound an alert." else "Now: every app except $count $apps can sound an alert."
    }
}

@Composable
private fun AlwaysAlertCard(count: Int, onChooseApps: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Always alert", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "These apps always make a sound — every notification, without the AI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (count == 0) "No apps yet" else "$count ${if (count == 1) "app" else "apps"}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            OutlinedButton(onClick = onChooseApps) { Text("Choose apps") }
        }
    }
}

@Composable
private fun ModeOption(label: String, supporting: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
