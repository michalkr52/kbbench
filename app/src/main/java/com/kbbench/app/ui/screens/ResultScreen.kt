package com.kbbench.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kbbench.app.viewmodel.BenchmarkResult
import com.kbbench.app.viewmodel.CameraViewModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(viewModel: CameraViewModel) {
    val results by viewModel.benchmarkResults.collectAsState()
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedIndex == null) "Benchmark Results" else results[selectedIndex!!].title) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedIndex != null) {
                            selectedIndex = null
                        } else {
                            viewModel.backToCamera()
                        }
                    }) {
                        Icon(
                            imageVector = if (selectedIndex == null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (selectedIndex == null) {
                        IconButton(onClick = { viewModel.exportResults() }) {
                            Icon(Icons.Default.Share, contentDescription = "Export")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (selectedIndex == null) {
            ResultGridView(
                results = results,
                onItemClick = { index -> selectedIndex = index },
                modifier = Modifier.padding(padding)
            )
        } else {
            ResultFullscreenView(
                results = results,
                initialIndex = selectedIndex!!,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
fun ResultGridView(
    results: List<BenchmarkResult>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        itemsIndexed(results) { index, result ->
            Card(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clickable { onItemClick(index) }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = result.imagePath,
                        contentDescription = result.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    ) {
                        Text(
                            text = result.title,
                            modifier = Modifier.padding(4.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ResultFullscreenView(
    results: List<BenchmarkResult>,
    initialIndex: Int,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { results.size })
    // Track scales for each page to disable pager scrolling when zoomed
    val pageScales = remember { mutableStateMapOf<Int, Float>() }
    val currentScale = pageScales[pagerState.currentPage] ?: 1f

    Column(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = currentScale <= 1f
        ) { page ->
            val result = results[page]
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            
            // Sync local scale to the map so pager knows
            LaunchedEffect(scale) {
                pageScales[page] = scale
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            var zoom = 1f
                            var pan = Offset.Zero
                            var pastTouchSlop = false
                            val touchSlop = viewConfiguration.touchSlop

                            awaitFirstDown()
                            do {
                                val event = awaitPointerEvent()
                                val canceled = event.changes.any { it.isConsumed }
                                if (!canceled) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()

                                    if (!pastTouchSlop) {
                                        zoom *= zoomChange
                                        pan += panChange
                                        val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                        val zoomMotion = abs(1 - zoom) * centroidSize
                                        val panMotion = pan.getDistance()

                                        if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                            pastTouchSlop = true
                                        }
                                    }

                                    if (pastTouchSlop) {
                                        val oldScale = scale
                                        // Only consume if we are zoomed in OR if it's a zoom gesture
                                        val isZooming = abs(1 - zoomChange) > 0.01f
                                        val shouldConsume = scale > 1.01f || isZooming

                                        if (shouldConsume) {
                                            scale = (scale * zoomChange).coerceIn(1f, 5f)
                                            val extraWidth = (scale - 1) * size.width
                                            val extraHeight = (scale - 1) * size.height
                                            val maxX = extraWidth / 2
                                            val maxY = extraHeight / 2

                                            offset = if (scale == oldScale) {
                                                Offset(
                                                    x = (offset.x + panChange.x).coerceIn(-maxX, maxX),
                                                    y = (offset.y + panChange.y).coerceIn(-maxY, maxY)
                                                )
                                            } else {
                                                Offset(
                                                    x = offset.x.coerceIn(-maxX, maxX),
                                                    y = offset.y.coerceIn(-maxY, maxY)
                                                )
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            } while (!canceled && event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 3f
                                }
                            }
                        )
                    }
            ) {
                AsyncImage(
                    model = result.imagePath,
                    contentDescription = result.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit
                )
            }
        }
        
        // Metrics overlay
        val currentResult = results[pagerState.currentPage]
        val displayMetrics = currentResult.metrics.toDisplayList()
        
        if (displayMetrics.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Metrics", style = MaterialTheme.typography.titleMedium)
                    displayMetrics.forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(key, style = MaterialTheme.typography.bodyMedium)
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
