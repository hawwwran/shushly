@file:OptIn(ExperimentalMaterial3Api::class)

package com.hawwwran.shushly.feature.aiconnection

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hawwwran.shushly.core.data.db.AppLearningEntity
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.feature.aiconnection.AiConnectionViewModel.TestStatus
import com.hawwwran.shushly.feature.common.OkColor

@Composable
fun AiConnectionScreen(
    viewModel: AiConnectionViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = viewModel.ui
    val learnings by viewModel.learnings.collectAsState()

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
                text = "Shushly classifies on your device by default. Add your own OpenAI API key to let " +
                    "OpenAI help decide which notifications are important. Your key is stored encrypted on " +
                    "this device and sent only to OpenAI.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = ui.apiKey,
                onValueChange = viewModel::onKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OpenAI API key") },
                singleLine = true,
                visualTransformation = if (ui.showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = viewModel::toggleShowKey) {
                        Icon(
                            imageVector = if (ui.showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (ui.showKey) "Hide key" else "Show key",
                        )
                    }
                },
                supportingText = { Text("Stored encrypted on this device; sent only to OpenAI.") },
            )

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

            HorizontalDivider()

            LearningsSection(learnings = learnings, onDelete = viewModel::deleteLearning)
        }
    }
}

@Composable
private fun LearningsSection(learnings: List<AppLearningEntity>, onDelete: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "What Shushly has learned from you",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (learnings.isEmpty()) {
            Text(
                text = "As you correct decisions in History, Shushly remembers per-app hints here and " +
                    "sends them to the AI for that app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        // observeAll() is ordered by app then newest-first, so groupBy keeps apps together + alphabetical.
        learnings.groupBy { it.packageName }.forEach { (_, items) ->
            LearningGroup(items = items, onDelete = onDelete)
        }
    }
}

@Composable
private fun LearningGroup(items: List<AppLearningEntity>, onDelete: (Long) -> Unit) {
    val appLabel = items.first().appLabel
    var expanded by rememberSaveable(items.first().packageName) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = appLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = items.size.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    items.forEach { item ->
                        LearningRow(item = item, onDelete = { onDelete(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LearningRow(item: AppLearningEntity, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DecisionChip(item.desiredDecision)
        Spacer(Modifier.width(10.dp))
        Text(
            text = item.digest,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete hint")
        }
    }
}

@Composable
private fun DecisionChip(desiredDecision: String) {
    val isAlert = desiredDecision == Decision.ALERT.name
    val container = if (isAlert) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (isAlert) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = container, shape = MaterialTheme.shapes.small) {
        Text(
            text = if (isAlert) "ALERT" else "SILENT",
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
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
                    ConnectedRow(model = ui.model, atMs = ui.lastVerifiedAtMs)
                } else {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Not connected yet — enter your OpenAI API key and tap Test connection.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
            text = "Connected" + (model?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
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
