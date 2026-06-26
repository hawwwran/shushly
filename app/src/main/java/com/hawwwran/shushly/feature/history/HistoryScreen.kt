@file:OptIn(ExperimentalMaterial3Api::class)

package com.hawwwran.shushly.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hawwwran.shushly.core.model.Decision
import com.hawwwran.shushly.core.model.DecisionReasonCode
import com.hawwwran.shushly.feature.common.OkColor
import com.hawwwran.shushly.feature.home.HomeViewModel
import com.hawwwran.shushly.service.listener.DecisionLogEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val log by viewModel.decisionLog.collectAsState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Decision history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Recent decisions (not yet saved across restarts).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            if (log.isEmpty()) {
                Text("No notifications processed yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(log) { entry ->
                        LogRow(entry)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

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

/** Renders one entry as a lifecycle: seen -> eligibility -> AI call -> decision. */
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
