package com.kbbench.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.kbbench.utils.RgbHistogram

@Composable
fun HistogramToolbar(
    showHistograms: Boolean,
    onToggleHistograms: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconToggleButton(
                checked = showHistograms,
                onCheckedChange = { onToggleHistograms() }
            ) {
                Icon(
                    imageVector = Icons.Filled.BarChart,
                    contentDescription = if (showHistograms) "Hide histograms" else "Show histograms"
                )
            }
            actions()
        }
    }
}

@Composable
fun HistogramOverlay(
    histogram: RgbHistogram,
    modifier: Modifier = Modifier
) {
    if (histogram.isEmpty) return

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 2.dp,
        shape = RectangleShape,
        modifier = modifier.size(width = 176.dp, height = 104.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
            ) {
                drawHistogramBars(histogram.red, Color.Red, histogram.maxCount)
                drawHistogramBars(histogram.green, Color.Green, histogram.maxCount)
                drawHistogramBars(histogram.blue, Color.Blue, histogram.maxCount)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = histogram.maxBrightness.toString(),
                        style = MaterialTheme.typography.labelSmall
                    )
            }
        }
    }
}

private fun DrawScope.drawHistogramBars(
    bins: IntArray,
    color: Color,
    maxCount: Int
) {
    if (maxCount <= 0) return

    val barWidth = size.width / bins.size
    bins.forEachIndexed { index, count ->
        val barHeight = size.height * count / maxCount.toFloat()
        if (barHeight > 0f) {
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = index * barWidth,
                    y = size.height - barHeight
                ),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                style = Fill,
                blendMode = BlendMode.Plus
            )
        }
    }
}
