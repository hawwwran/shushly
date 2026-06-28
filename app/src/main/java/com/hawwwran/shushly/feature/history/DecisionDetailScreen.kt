@file:OptIn(ExperimentalMaterial3Api::class)

package com.hawwwran.shushly.feature.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hawwwran.shushly.BuildConfig
import com.hawwwran.shushly.core.data.db.DecisionHistoryEntity

@Composable
fun DecisionDetailScreen(
    viewModel: HistoryViewModel,
    id: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(id) { viewModel.loadDetail(id) }
    val detail = viewModel.detail
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val message = viewModel.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val e = detail.entry
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
                SteeringSection(
                    steering = steeringFor(
                        entry = e,
                        settings = detail.settings,
                        hasCachedContent = detail.hasCachedContent,
                        hasExistingLearning = detail.learning != null,
                        aiUsable = detail.aiUsable,
                    ),
                    appLabel = e.appLabel,
                    learningDigest = detail.learning?.digest,
                    busy = detail.busy,
                    onCorrect = viewModel::correct,
                    onUndo = viewModel::clearCorrection,
                    onConfig = viewModel::applyConfig,
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
private fun SteeringSection(
    steering: Steering,
    appLabel: String,
    learningDigest: String?,
    busy: Boolean,
    onCorrect: (SmartCorrection) -> Unit,
    onUndo: () -> Unit,
    onConfig: (ConfigAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Was this right?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        val smart = steering.smart
        if (steering.showSmart && smart != null) {
            if (steering.alreadyCorrected) {
                CorrectionSavedCard(smart, appLabel, learningDigest, onUndo)
            } else {
                Button(
                    onClick = { onCorrect(smart) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(smart.label())
                }
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    SubtleText("Asking the AI to summarise this…")
                } else {
                    SubtleText("Shushly will ask the AI for a short, private summary and remember it for $appLabel.")
                }
            }
        }

        steering.configActions.forEach { action ->
            OutlinedButton(onClick = { onConfig(action) }, modifier = Modifier.fillMaxWidth()) {
                Text(action.label(appLabel))
            }
        }

        steering.explanation?.let { SubtleText(it) }
        if (!steering.hasAnything) {
            SubtleText("Nothing to adjust for this one.")
        }
    }
}

@Composable
private fun CorrectionSavedCard(
    smart: SmartCorrection,
    appLabel: String,
    learningDigest: String?,
    onUndo: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (smart) {
                        SmartCorrection.SHOULD_ALERT -> "Saved — this should have alerted."
                        SmartCorrection.SHOULD_SILENT -> "Saved — this should have stayed silent."
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            learningDigest?.let {
                Text(
                    text = "Shushly will weigh “$it” for $appLabel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            TextButton(onClick = onUndo, modifier = Modifier.align(Alignment.End)) { Text("Undo") }
        }
    }
}

@Composable
private fun SubtleText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
