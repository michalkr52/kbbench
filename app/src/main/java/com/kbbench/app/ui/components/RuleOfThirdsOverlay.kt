package com.kbbench.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun RuleOfThirdsToolbar(
    showGrid: Boolean,
    onToggleGrid: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        IconToggleButton(
            checked = showGrid,
            onCheckedChange = { onToggleGrid() }
        ) {
            Icon(
                imageVector = Icons.Filled.Grid3x3,
                contentDescription = if (showGrid) "Hide rule of thirds grid" else "Show rule of thirds grid"
            )
        }
    }
}

@Composable
fun RuleOfThirdsOverlay(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val lineColor = Color.White.copy(alpha = 0.5f)
        val strokeWidth = 2.dp.toPx()
        val verticalThird = size.width / 3f
        val horizontalThird = size.height / 3f
        val gridPath = Path().apply {
            moveTo(verticalThird, 0f)
            lineTo(verticalThird, size.height)
            moveTo(verticalThird * 2f, 0f)
            lineTo(verticalThird * 2f, size.height)
            moveTo(0f, horizontalThird)
            lineTo(size.width, horizontalThird)
            moveTo(0f, horizontalThird * 2f)
            lineTo(size.width, horizontalThird * 2f)
        }

        drawPath(
            path = gridPath,
            color = lineColor,
            style = Stroke(width = strokeWidth),
            blendMode = BlendMode.Difference
        )
    }
}
