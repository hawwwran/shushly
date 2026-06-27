@file:OptIn(ExperimentalMaterial3Api::class)

package com.hawwwran.shushly.feature.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hawwwran.shushly.BuildConfig
import com.hawwwran.shushly.core.data.db.DecisionHistoryEntity

private const val FEEDBACK_SHOULD_ALERT = "SHOULD_ALERT"
private const val FEEDBACK_SHOULD_SILENT = "SHOULD_SILENT"

@Composable
fun DecisionDetailScreen(
    viewModel: HistoryViewModel,
    id: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(id) { viewModel.loadDetail(id) }
    val entry = viewModel.detailEntry
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Decision detail") },
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
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val e = entry
            if (e == null) {
                Text("Entry not found.", style = MaterialTheme.typography.bodyMedium)
            } else {
                DetailField("App", e.appLabel)
                DetailField("Package", e.packageName)
                DetailField("Time", formatDateTime(e.createdAtMs))
                HorizontalDivider()
                DetailField("Decision", decisionLabel(e))
                DetailField("Reason code", e.reasonCode)
                DetailField("Lifecycle", lifecycleText(e), monospace = true)
                e.userVisibleReason?.let { DetailField("Reason", it) }
                HorizontalDivider()
                DetailField("AI called", if (e.aiCalled) "Yes" else "No")
                DetailField("Sounded", if (e.wasAlerted) "Yes" else "No")
                e.notificationKeyHash?.let { DetailField("Source key (hash)", it) }

                HorizontalDivider()
                FeedbackSection(
                    current = e.userFeedback,
                    onSelect = { value -> viewModel.setFeedback(e.id, value) },
                )

                if (BuildConfig.DEBUG) {
                    HorizontalDivider()
                    OutlinedButton(onClick = { copyTechnicalDetails(context, e) }) {
                        Text("Copy technical details")
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackSection(current: String?, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Was this right?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        FeedbackChip(
            label = "This should have alerted",
            selected = current == FEEDBACK_SHOULD_ALERT,
            // Tapping the selected one again clears it.
            onClick = { onSelect(if (current == FEEDBACK_SHOULD_ALERT) null else FEEDBACK_SHOULD_ALERT) },
        )
        FeedbackChip(
            label = "This should have stayed silent",
            selected = current == FEEDBACK_SHOULD_SILENT,
            onClick = { onSelect(if (current == FEEDBACK_SHOULD_SILENT) null else FEEDBACK_SHOULD_SILENT) },
        )
        Text(
            text = "Saved on this device only. Shushly doesn't learn from this yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeedbackChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Done, contentDescription = null) }
        } else {
            null
        },
    )
}

@Composable
private fun DetailField(label: String, value: String, monospace: Boolean = false) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            fontWeight = FontWeight.Normal,
        )
    }
}

/** Debug-only: copy the entry's already-redacted fields (no raw title/body — there is none). */
private fun copyTechnicalDetails(context: Context, e: DecisionHistoryEntity) {
    val text = buildString {
        appendLine("Shushly decision")
        appendLine("App: ${e.appLabel} (${e.packageName})")
        appendLine("Time: ${formatDateTime(e.createdAtMs)}")
        appendLine("Decision: ${decisionLabel(e)}")
        appendLine("Reason code: ${e.reasonCode}")
        appendLine("AI called: ${if (e.aiCalled) "yes" else "no"}")
        appendLine("Sounded: ${if (e.wasAlerted) "yes" else "no"}")
        appendLine("Source key (hash): ${e.notificationKeyHash ?: "—"}")
        append("Feedback: ${e.userFeedback ?: "none"}")
    }
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Shushly decision", text))
    Toast.makeText(context, "Copied technical details", Toast.LENGTH_SHORT).show()
}
