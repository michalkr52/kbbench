package com.kbbench.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kbbench.algorithm.preprocessing.HighlightMode
import com.kbbench.algorithm.preprocessing.LensShadingMode
import com.kbbench.algorithm.preprocessing.PreprocessingConfig
import com.kbbench.algorithm.preprocessing.TransferCurve
import com.kbbench.algorithm.preprocessing.TransferEncoding
import com.kbbench.algorithm.preprocessing.WhiteBalanceGains
import com.kbbench.algorithm.preprocessing.WhiteBalanceMode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreprocessingSettingsDialog(
    initial: PreprocessingConfig,
    onConfirm: (PreprocessingConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var config by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preprocessing", modifier = Modifier.weight(1f))
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Reset preprocessing") } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(onClick = { config = PreprocessingConfig() }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Reset preprocessing")
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProfileChoice("Transfer", config.transferCurve.encoding, listOf(
                    TransferEncoding.SRGB to "sRGB", TransferEncoding.LINEAR to "Linear", TransferEncoding.LOG to "Log",
                )) { config = config.copy(transferCurve = TransferCurve(it, config.transferCurve.logStrength)) }
                if (config.transferCurve.encoding == TransferEncoding.LOG) {
                    ProfileNumber("Log strength", config.transferCurve.logStrength, 1f..100f, 98) {
                        config = config.copy(transferCurve = config.transferCurve.copy(logStrength = it))
                    }
                }
                ProfileNumber("Exposure offset (EV)", config.exposureOffsetEv, -4f..4f, 31) {
                    config = config.copy(exposureOffsetEv = it)
                }
                Text("RAW only", style = MaterialTheme.typography.titleSmall)
                ProfileChoice("White balance", config.whiteBalance, listOf(
                    WhiteBalanceMode.METADATA to "Metadata", WhiteBalanceMode.IDENTITY to "Identity", WhiteBalanceMode.MANUAL to "Manual",
                )) { config = config.copy(whiteBalance = it) }
                if (config.whiteBalance == WhiteBalanceMode.MANUAL) {
                    val gains = config.manualWhiteBalance.relativeToGreen()
                    ProfileNumber("Red / green", gains.red.toDouble(), 0.25f..8f, 154) {
                        config = config.copy(manualWhiteBalance = WhiteBalanceGains(it.toFloat(), 1f, gains.blue))
                    }
                    ProfileNumber("Blue / green", gains.blue.toDouble(), 0.25f..8f, 154) {
                        config = config.copy(manualWhiteBalance = WhiteBalanceGains(gains.red, 1f, it.toFloat()))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Lens shading (metadata)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = config.lensShading == LensShadingMode.METADATA,
                        onCheckedChange = { config = config.copy(lensShading = if (it) LensShadingMode.METADATA else LensShadingMode.OFF) },
                    )
                }
                ProfileChoice("Highlights", config.highlights, listOf(
                    HighlightMode.NEUTRALIZE_CLIPPED to "Neutralize clipped", HighlightMode.CLIP_CHANNELS to "Clip channels",
                )) { config = config.copy(highlights = it) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(config) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun <Value> ProfileChoice(label: String, selected: Value, options: List<Pair<Value, String>>, onSelect: (Value) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Column(modifier = Modifier.weight(1f)) {
            TextButton(onClick = { expanded = true }) { Text(options.first { it.first == selected }.second) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { onSelect(value); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun ProfileNumber(label: String, value: Double, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Double) -> Unit) {
    Column {
        Text("$label: ${"%.2f".format(Locale.US, value)}")
        Slider(value = value.toFloat(), onValueChange = { onChange(it.toDouble()) }, valueRange = range, steps = steps)
    }
}
