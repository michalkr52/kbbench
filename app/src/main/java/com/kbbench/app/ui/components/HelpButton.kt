package com.kbbench.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpButton(title: String, sections: List<Pair<String, String>>) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(title) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = { showHelp = true }) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = title)
        }
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            modifier = Modifier.fillMaxWidth(0.92f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text(title) },
            text = {
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .subtleVerticalScrollbar(
                            scrollState = scrollState,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        sections.forEach { (heading, body) ->
                            if (heading.isBlank()) {
                                Text(body, style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        heading,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                    Text(body, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text("Close") }
            },
        )
    }
}

private fun Modifier.subtleVerticalScrollbar(scrollState: ScrollState, color: Color): Modifier =
    drawWithContent {
        drawContent()
        if (scrollState.maxValue > 0) {
            val trackWidth = 2.dp.toPx()
            val trackInset = 1.dp.toPx()
            val trackHeight = size.height - (trackInset * 2)
            val thumbHeight = (size.height * size.height / (size.height + scrollState.maxValue))
                .coerceAtLeast(24.dp.toPx())
                .coerceAtMost(trackHeight)
            val thumbTravel = trackHeight - thumbHeight
            val thumbTop = trackInset +
                (scrollState.value.toFloat() / scrollState.maxValue * thumbTravel)
            drawRoundRect(
                color = color.copy(alpha = 0.12f),
                topLeft = androidx.compose.ui.geometry.Offset(size.width - trackWidth, trackInset),
                size = androidx.compose.ui.geometry.Size(trackWidth, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackWidth),
            )
            drawRoundRect(
                color = color.copy(alpha = 0.45f),
                topLeft = androidx.compose.ui.geometry.Offset(size.width - trackWidth, thumbTop),
                size = androidx.compose.ui.geometry.Size(trackWidth, thumbHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackWidth),
            )
        }
    }
