package com.hawwwran.shushly

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import com.hawwwran.shushly.feature.home.HomeViewModel
import com.hawwwran.shushly.feature.home.ReadinessUi
import com.hawwwran.shushly.service.listener.DecisionLogEntry
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SpikeScreen(
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}

private val OkColor = Color(0xFF2E7D32)

@Composable
private fun SpikeScreen(viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by viewModel.settingsState.collectAsState()
    val log by viewModel.decisionLog.collectAsState()
    val readiness = viewModel.readinessUi

    val postNotifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Shushly",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Quiet by default. Important by exception. (Phase-0 spike)",
            style = MaterialTheme.typography.bodyMedium,
        )

        ReadinessCard(
            readiness = readiness,
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

        DebugCard(
            quietOn = settings.smartQuietModeEnabled,
            onFireAlert = { viewModel.fireTest(alert = true) },
            onFireSilent = { viewModel.fireTest(alert = false) },
        )

        DecisionLogCard(log = log)
    }
}

@Composable
private fun ReadinessCard(
    readiness: ReadinessUi,
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
private fun DebugCard(quietOn: Boolean, onFireAlert: () -> Unit, onFireSilent: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Developer test", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (quietOn) {
                    "Push a synthesized notification through the real pipeline."
                } else {
                    "Turn on Smart Quiet Mode first; otherwise these are skipped."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onFireAlert, modifier = Modifier.weight(1f)) { Text("Fire TEST_ALERT") }
                OutlinedButton(onClick = onFireSilent, modifier = Modifier.weight(1f)) { Text("Fire TEST_SILENT") }
            }
        }
    }
}

private const val COLLAPSED_LOG_COUNT = 8
private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
private fun DecisionLogCard(log: List<DecisionLogEntry>) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Processing log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (log.size > COLLAPSED_LOG_COUNT) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Show less" else "Show all (${log.size})")
                    }
                }
            }
            if (log.isEmpty()) {
                Text("No notifications processed yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                val shown = if (expanded) log else log.take(COLLAPSED_LOG_COUNT)
                shown.forEach { entry ->
                    LogRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: DecisionLogEntry) {
    val decisionColor = when (entry.decision) {
        Decision.ALERT -> OkColor
        Decision.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = decisionLabel(entry.decision),
                fontWeight = FontWeight.Bold,
                color = decisionColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = timeFormatter.format(Instant.ofEpochMilli(entry.timeMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${entry.appLabel} · ${entry.packageName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = lifecycleText(entry),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        entry.userVisibleReason?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun decisionLabel(decision: Decision): String = when (decision) {
    Decision.ALERT -> "ALERT"
    Decision.SILENT -> "SILENT"
    Decision.SKIPPED -> "SKIPPED"
    Decision.ERROR -> "ERROR"
    Decision.WOULD_ALERT -> "WOULD ALERT"
}

/** Renders one entry as a lifecycle: seen -> eligibility -> AI call -> decision (spec C). */
private fun lifecycleText(entry: DecisionLogEntry): String {
    if (!entry.aiCalled) {
        val why = when (entry.reasonCode) {
            DecisionReasonCode.SKIPPED_QUIET_MODE_OFF -> "Smart Quiet Mode off"
            DecisionReasonCode.SKIPPED_PROTECTED_SOURCE -> "protected source"
            DecisionReasonCode.SKIPPED_NOT_ELIGIBLE -> "not eligible"
            DecisionReasonCode.SKIPPED_NO_USABLE_TEXT -> "no usable text"
            DecisionReasonCode.SKIPPED_DUPLICATE -> "duplicate (AI cooldown)"
            DecisionReasonCode.SILENT_GROUP_SUMMARY -> "group summary"
            else -> entry.reasonCode.name.lowercase().replace('_', ' ')
        }
        return "seen → stopped: $why → no AI call"
    }
    val outcome = when (entry.decision) {
        Decision.ALERT -> if (entry.wasAlerted) "ALERT → sounded" else "ALERT"
        Decision.SILENT -> "SILENT"
        Decision.WOULD_ALERT -> "WOULD ALERT (simulation)"
        Decision.ERROR -> "ERROR → stayed silent"
        Decision.SKIPPED ->
            if (entry.reasonCode == DecisionReasonCode.SKIPPED_RATE_LIMIT) "ALERT but rate-limited (held back)"
            else "skipped"
    }
    return "seen → eligible → AI called → $outcome"
}

private fun Context.startSafely(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private fun Context.openAppNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    startSafely(intent)
}
