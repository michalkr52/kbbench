package com.kbbench.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
 import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kbbench.app.viewmodel.BenchmarkResult
import com.kbbench.app.viewmodel.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val results by viewModel.benchmarkResults.collectAsState()
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var currentPage by remember { mutableIntStateOf(0) }

    BackHandler {
        if (selectedIndex != null) {
            selectedIndex = null
        } else {
            viewModel.backToCamera()
        }
    }

    val displayTitle = if (selectedIndex == null) "Benchmark Results" else results[currentPage].title

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayTitle) },
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
                        IconButton(onClick = { viewModel.exportResults(context) }) {
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
                onItemClick = { index ->
                    selectedIndex = index
                    currentPage = index
                },
                modifier = Modifier.padding(padding)
            )
        } else {
            ResultFullscreenView(
                results = results,
                initialIndex = selectedIndex!!,
                onPageChanged = { page -> currentPage = page },
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
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = result.rotationDegrees.toFloat() },
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
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { results.size })
    var isPagerScrollEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    Column(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = isPagerScrollEnabled
        ) { page ->
            val result = results[page]
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }

            LaunchedEffect(scale) {
                isPagerScrollEnabled = scale <= 1.01f
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                    isPagerScrollEnabled = true
                                } else {
                                    scale = 3f
                                    isPagerScrollEnabled = false
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val changes = event.changes

                                if (changes.size >= 2) {
                                    // Pinch gesture: handle zoom + pan
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                                    scale = newScale

                                    if (newScale > 1f) {
                                        val maxX = size.width * (newScale - 1f) / 2f
                                        val maxY = size.height * (newScale - 1f) / 2f
                                        offset = Offset(
                                            (offset.x + pan.x).coerceIn(-maxX, maxX),
                                            (offset.y + pan.y).coerceIn(-maxY, maxY)
                                        )
                                    } else {
                                        offset = Offset.Zero
                                    }
                                    changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (changes.size == 1 && scale > 1.01f) {
                                    // Single finger pan when zoomed in
                                    val pan = event.calculatePan()
                                    val maxX = size.width * (scale - 1f) / 2f
                                    val maxY = size.height * (scale - 1f) / 2f
                                    offset = Offset(
                                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                                        (offset.y + pan.y).coerceIn(-maxY, maxY)
                                    )
                                    changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                // When not zoomed + single finger: don't consume → pager handles swipe
                            } while (changes.any { it.pressed })
                        }
                    }
            ) {
                AsyncImage(
                    model = result.imagePath,
                    contentDescription = result.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = result.rotationDegrees.toFloat()
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
