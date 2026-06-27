@file:OptIn(ExperimentalMaterial3Api::class)

package com.hawwwran.shushly.feature.aiconnection

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hawwwran.shushly.BuildConfig
import com.hawwwran.shushly.feature.aiconnection.AiConnectionViewModel.RelayPreset
import com.hawwwran.shushly.feature.aiconnection.AiConnectionViewModel.TestStatus
import com.hawwwran.shushly.feature.common.OkColor

@Composable
fun AiConnectionScreen(
    viewModel: AiConnectionViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = viewModel.ui

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("AI connection") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!ui.loaded) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Shushly classifies on your device by default and sends nothing off your phone. " +
                    "Connect a relay to let an AI help decide which notifications are important; only " +
                    "redacted notification details are sent, never your whole inbox.",
                style = MaterialTheme.typography.bodyMedium,
            )

            ConnectionTypeCard(preset = ui.preset, onPresetChange = viewModel::onPresetChange)

            OutlinedTextField(
                value = ui.relayUrl,
                onValueChange = viewModel::onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Relay URL") },
                singleLine = true,
                isError = ui.relayUrl.isNotBlank() && !isAcceptableUrl(ui.relayUrl.trim()),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    Text(
                        if (ui.relayUrl.isNotBlank() && !isAcceptableUrl(ui.relayUrl.trim())) {
                            "Use https:// (http:// is allowed only for 127.0.0.1 / localhost in debug)."
                        } else {
                            "Must start with https://. In debug, http:// is allowed for 127.0.0.1 / localhost."
                        },
                    )
                },
            )

            OutlinedTextField(
                value = ui.token,
                onValueChange = viewModel::onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Device token") },
                singleLine = true,
                visualTransformation = if (ui.showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = viewModel::toggleShowToken) {
                        Icon(
                            imageVector = if (ui.showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (ui.showToken) "Hide token" else "Show token",
                        )
                    }
                },
                supportingText = { Text("Saved securely on your device. Sent to the relay only as a bearer token.") },
            )

            if (BuildConfig.DEBUG && BuildConfig.DEV_RELAY_URL.isNotEmpty()) {
                TextButton(
                    onClick = { viewModel.fillDevRelay(BuildConfig.DEV_RELAY_URL, BuildConfig.DEV_DEVICE_TOKEN) },
                ) {
                    Text("Fill dev relay (localhost)")
                }
            }

            OutlinedTextField(
                value = ui.customInstruction,
                onValueChange = viewModel::onInstructionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Custom instructions (optional)") },
                minLines = 3,
                supportingText = {
                    Text(
                        "Add a note to steer what counts as important for you (e.g. \"I'm on call — treat " +
                            "outage alerts as critical\"). It refines, but can't override, the safety rules.",
                    )
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = viewModel::test,
                    enabled = ui.testStatus != TestStatus.Testing,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Test connection")
                }
                OutlinedButton(
                    onClick = viewModel::save,
                    enabled = ui.testStatus != TestStatus.Testing,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save")
                }
            }

            StatusCard(ui)
        }
    }
}

@Composable
private fun ConnectionTypeCard(preset: RelayPreset, onPresetChange: (RelayPreset) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connection type", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            RadioRow(
                label = "Shushly relay (recommended)",
                supporting = "Send redacted notification details to a relay you trust. Needs a relay URL and a device token.",
                selected = preset == RelayPreset.RECOMMENDED,
                enabled = true,
                onClick = { onPresetChange(RelayPreset.RECOMMENDED) },
            )
            RadioRow(
                label = "Private / self-hosted relay",
                supporting = "Point Shushly at your own relay deployment.",
                selected = preset == RelayPreset.SELF_HOSTED,
                enabled = true,
                onClick = { onPresetChange(RelayPreset.SELF_HOSTED) },
            )
            RadioRow(
                label = "Direct API key (development only)",
                supporting = "Not available in this build.",
                selected = false,
                enabled = false,
                onClick = {},
            )
        }
    }
}

@Composable
private fun RadioRow(
    label: String,
    supporting: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val rowModifier = if (enabled) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()
    val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = if (enabled) onClick else null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = contentColor)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusCard(ui: AiConnectionViewModel.UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (val status = ui.testStatus) {
                TestStatus.Testing -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Testing connection…", style = MaterialTheme.typography.bodyMedium)
                }
                is TestStatus.Success -> ConnectedRow(status.model, ui.lastVerifiedAtMs)
                is TestStatus.Failure -> {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text("Couldn't connect — ${status.reason}", style = MaterialTheme.typography.bodyMedium)
                }
                TestStatus.Idle -> if (ui.isVerified) {
                    ConnectedRow(model = null, atMs = ui.lastVerifiedAtMs)
                } else {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("Not configured yet — enter a relay and tap Test connection.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ConnectedRow(model: String?, atMs: Long?) {
    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = OkColor)
    Spacer(Modifier.width(12.dp))
    Column {
        Text(
            text = "Connected" + (model?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (atMs != null) {
            val rel = DateUtils.getRelativeTimeSpanString(
                atMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
            Text("Last verified $rel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** https:// always; http:// only for loopback/emulator hosts in a debug build. */
private fun isAcceptableUrl(url: String): Boolean {
    if (url.startsWith("https://")) return true
    if (BuildConfig.DEBUG) {
        return url.startsWith("http://127.0.0.1") ||
            url.startsWith("http://localhost") ||
            url.startsWith("http://10.0.2.2")
    }
    return false
}
