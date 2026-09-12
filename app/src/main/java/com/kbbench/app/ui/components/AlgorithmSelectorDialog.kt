package com.kbbench.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kbbench.algorithm.base.AlgorithmMetadata
import com.kbbench.algorithm.base.AlgorithmParameter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Algorithm picker with per-algorithm tuning sliders.
 *
 * Rows are rendered from [AlgorithmMetadata.parameters], so an algorithm gains controls purely by
 * declaring them in the algorithm module.
 *
 * @param parameterValues Overrides only, keyed by algorithm name then parameter id; parameters
 *   absent from the map sit at their declared defaults.
 * @param onParameterCommit Called when a drag ends, so settings are persisted once per gesture.
 */
@Composable
fun AlgorithmSelectorDialog(
    algorithms: List<AlgorithmMetadata>,
    enabledAlgorithmNames: Set<String>,
    parameterValues: Map<String, Map<String, Double>>,
    onAlgorithmEnabledChanged: (String, Boolean) -> Unit,
    onAllAlgorithmsEnabledChanged: (Boolean) -> Unit,
    onParameterChanged: (String, String, Double) -> Unit,
    onParameterCommit: () -> Unit,
    onResetParameters: (String) -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val allEnabled = algorithms.isNotEmpty() &&
        algorithms.all { it.name in enabledAlgorithmNames }
    var expandedAlgorithm by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Algorithms") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("All algorithms", modifier = Modifier.weight(1f))
                    Checkbox(
                        checked = allEnabled,
                        onCheckedChange = onAllAlgorithmsEnabledChanged
                    )
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(algorithms, key = { it.name }) { algorithm ->
                        AlgorithmRow(
                            algorithm = algorithm,
                            enabled = algorithm.name in enabledAlgorithmNames,
                            overrides = parameterValues[algorithm.name].orEmpty(),
                            expanded = expandedAlgorithm == algorithm.name,
                            onExpandToggle = {
                                expandedAlgorithm =
                                    if (expandedAlgorithm == algorithm.name) null else algorithm.name
                            },
                            onEnabledChanged = { onAlgorithmEnabledChanged(algorithm.name, it) },
                            onParameterChanged = { id, value ->
                                onParameterChanged(algorithm.name, id, value)
                            },
                            onParameterCommit = onParameterCommit,
                            onReset = { onResetParameters(algorithm.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun AlgorithmRow(
    algorithm: AlgorithmMetadata,
    enabled: Boolean,
    overrides: Map<String, Double>,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onParameterChanged: (String, Double) -> Unit,
    onParameterCommit: () -> Unit,
    onReset: () -> Unit,
) {
    val hasParameters = algorithm.parameters.isNotEmpty()
    val isCustomised = algorithm.parameters.any { parameter ->
        overrides[parameter.id]?.let { it != parameter.default } == true
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (hasParameters) Modifier.clickable(onClick = onExpandToggle) else Modifier)
            ) {
                Text(
                    text = algorithm.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (isCustomised) "${algorithm.kind} - custom" else algorithm.kind,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (hasParameters) {
                IconButton(onClick = onExpandToggle) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Hide parameters" else "Show parameters"
                    )
                }
            }
            Checkbox(
                checked = enabled,
                onCheckedChange = onEnabledChanged
            )
        }

        if (expanded && hasParameters) {
            algorithm.parameters.forEach { parameter ->
                ParameterSlider(
                    parameter = parameter,
                    value = overrides[parameter.id] ?: parameter.default,
                    onValueChange = { onParameterChanged(parameter.id, it) },
                    onValueChangeFinished = onParameterCommit,
                )
            }
            if (isCustomised) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onReset) {
                        Text("Reset to defaults")
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ParameterSlider(
    parameter: AlgorithmParameter,
    value: Double,
    onValueChange: (Double) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    // Slider reports the endpoints plus this many interior stops.
    val steps = remember(parameter) {
        (((parameter.max - parameter.min) / parameter.step).roundToInt() - 1).coerceAtLeast(0)
    }

    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = parameter.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "%.${parameter.decimals}f".format(Locale.US, value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toDouble()) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = parameter.min.toFloat()..parameter.max.toFloat(),
            steps = steps,
        )
        parameter.description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
