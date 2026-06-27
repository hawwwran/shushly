@file:OptIn(ExperimentalMaterial3Api::class)

package com.hawwwran.shushly.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hawwwran.shushly.core.data.db.DecisionHistoryEntity

@Composable
fun DecisionDetailScreen(
    viewModel: HistoryViewModel,
    id: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry by produceState<DecisionHistoryEntity?>(initialValue = null, id) {
        value = viewModel.getById(id)
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
            }
        }
    }
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
