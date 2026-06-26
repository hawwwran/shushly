@file:OptIn(ExperimentalMaterial3Api::class)

package com.hawwwran.shushly.feature.onboarding

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hawwwran.shushly.feature.common.OkColor
import com.hawwwran.shushly.feature.common.openAppNotificationSettings
import com.hawwwran.shushly.feature.common.startSafely
import com.hawwwran.shushly.feature.home.HomeViewModel

private const val STEP_COUNT = 3

@Composable
fun OnboardingScreen(
    viewModel: HomeViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (step) {
                    0 -> ValuePropStep()
                    1 -> HowItWorksStep()
                    else -> PermissionsStep(viewModel)
                }
            }
            Spacer(Modifier.height(16.dp))
            StepDots(current = step)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    TextButton(onClick = { step-- }) { Text("Back") }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    if (step < STEP_COUNT - 1) {
                        step++
                    } else {
                        viewModel.completeOnboarding()
                        onFinished()
                    }
                }) {
                    Text(if (step < STEP_COUNT - 1) "Continue" else "Start using Shushly")
                }
            }
        }
    }
}

@Composable
private fun ValuePropStep() {
    Text(
        text = "Quiet by default.\nImportant by exception.",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "Shushly keeps everyday notifications quiet. When something looks important, it plays a sound so you don't miss it.",
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun HowItWorksStep() {
    Text("How it works", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
        text = "Shushly switches your phone into a Quiet Mode that silences ordinary notifications. Your apps' notifications still arrive in the shade — Shushly doesn't remove them.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = "When a notification looks important, Shushly plays a sound on the alarm channel, so you hear it even while Quiet Mode is on. There's no pop-up — the sound is the signal.",
        style = MaterialTheme.typography.bodyMedium,
    )
    HorizontalDivider()
    Text("About the AI decision", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        text = "Right now, Shushly decides on your device and sends nothing off your phone.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = "When the AI connection is set up in a later update, the title and text of notifications from the apps you choose may be sent to the configured AI service to decide whether to alert you. Nothing is sent until you set that up and pick apps.",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun PermissionsStep(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val r = viewModel.readinessUi
    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    Text("Finish setup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
        text = "Grant these so Shushly can work. You can change them later in system settings.",
        style = MaterialTheme.typography.bodyMedium,
    )

    PermissionRow(
        label = "Notification access",
        why = "Lets Shushly see notifications so it can keep them quiet and spot important ones.",
        granted = r.listenerEnabled,
        required = true,
        onOpen = { context.startSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
    )
    PermissionRow(
        label = "Quiet-mode (DND) access",
        why = "Lets Shushly switch your phone into Quiet Mode.",
        granted = r.policyAccessGranted,
        required = true,
        onOpen = { context.startSafely(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
    )
    PermissionRow(
        label = "Notifications allowed for Shushly",
        why = "Optional — only for occasional status messages. Alerts don't need it.",
        granted = r.postNotificationsGranted,
        required = false,
        onOpen = {
            if (Build.VERSION.SDK_INT >= 33) {
                postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.openAppNotificationSettings()
            }
        },
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Alerts are a sound", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Shushly plays a sound on the alarm channel, so keep your alarm volume up.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!r.alarmAudible) {
                Text(
                    text = "Alarm volume is currently off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (!(r.listenerEnabled && r.policyAccessGranted)) {
        Text(
            text = "You can finish now and grant the required ones later — Smart Quiet Mode stays off until they're granted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    why: String,
    granted: Boolean,
    required: Boolean,
    onOpen: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = when {
                    granted -> Icons.Filled.CheckCircle
                    required -> Icons.Filled.Warning
                    else -> Icons.Filled.Info
                },
                contentDescription = null,
                tint = when {
                    granted -> OkColor
                    required -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!granted) {
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onOpen) { Text("Open") }
            }
        }
    }
}

@Composable
private fun StepDots(current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(STEP_COUNT) { i ->
            val color = if (i <= current) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
