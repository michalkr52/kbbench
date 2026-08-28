package com.kbbench.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Flip
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kbbench.app.viewmodel.BenchmarkResult
import com.kbbench.app.viewmodel.CameraViewModel
import com.kbbench.app.ui.components.HistogramOverlay
import com.kbbench.app.ui.components.HistogramToolbar
import com.kbbench.utils.RgbHistogram

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val results by viewModel.benchmarkResults.collectAsState()
    val referenceImage by viewModel.referenceImage.collectAsState()
    val referenceIndex = results.indexOfFirst { it.id == "reference" }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var currentPage by remember { mutableIntStateOf(0) }
    var isSelectingForCompare by remember { mutableStateOf(false) }
    var compareSelection by remember { mutableStateOf(setOf<Int>()) }
    var compareIndices by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var isCompareVertical by remember { mutableStateOf(true) }
    var showHistograms by remember { mutableStateOf(false) }
    val histograms by viewModel.histograms.collectAsState()

    val loadReferenceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let { viewModel.loadReferenceImage(context, it) } }
    )

    BackHandler {
        when {
            compareIndices != null -> {
                compareIndices = null
            }
            isSelectingForCompare -> {
                isSelectingForCompare = false
                compareSelection = emptySet()
            }
            selectedIndex != null -> {
                selectedIndex = null
            }
            else -> {
                viewModel.backToCamera()
            }
        }
    }

    val displayTitle = when {
        compareIndices != null -> "Comparison"
        isSelectingForCompare -> "Select two images"
        selectedIndex != null -> results[currentPage].title
        else -> "Benchmark Results"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayTitle) },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            compareIndices != null -> {
                                compareIndices = null
                            }
                            isSelectingForCompare -> {
                                isSelectingForCompare = false
                                compareSelection = emptySet()
                            }
                            selectedIndex != null -> {
                                selectedIndex = null
                            }
                            else -> {
                                viewModel.backToCamera()
                            }
                        }
                    }) {
                        Icon(
                            imageVector = if (selectedIndex == null && !isSelectingForCompare && compareIndices == null)
                                Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (compareIndices != null) {
                        IconButton(onClick = { isCompareVertical = !isCompareVertical }) {
                            Icon(
                                imageVector = Icons.Default.Flip,
                                contentDescription = if (isCompareVertical) "Switch to horizontal" else "Switch to vertical"
                            )
                        }
                    } else if (selectedIndex == null && !isSelectingForCompare) {
                        IconButton(onClick = { viewModel.exportResults(context) }) {
                            Icon(Icons.Default.Share, contentDescription = "Export")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            compareIndices != null -> {
                val (a, b) = compareIndices!!
                CompareView(
                    resultA = results[a],
                    resultB = results[b],
                    isVertical = isCompareVertical,
                    histograms = histograms,
                    showHistograms = showHistograms,
                    onToggleHistograms = { showHistograms = !showHistograms },
                    modifier = Modifier.padding(padding)
                )
            }
            selectedIndex != null -> {
                ResultFullscreenView(
                    results = results,
                    initialIndex = selectedIndex!!,
                    onPageChanged = { page -> currentPage = page },
                    referenceIndex = referenceIndex,
                    onCompareWithReference = { page -> compareIndices = Pair(referenceIndex, page) },
                    histograms = histograms,
                    showHistograms = showHistograms,
                    onToggleHistograms = { showHistograms = !showHistograms },
                    modifier = Modifier.padding(padding)
                )
            }
            else -> {
                Column(modifier = Modifier.padding(padding)) {
                    if (referenceImage == null) {
                        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Load a reference image to calculate quality metrics",
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        loadReferenceLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            CircleShape
                                        )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FileUpload,
                                        contentDescription = "Load reference image",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                    ResultGridView(
                        results = results,
                        isSelectingForCompare = isSelectingForCompare,
                        compareSelection = compareSelection,
                        onItemClick = { index ->
                            if (isSelectingForCompare) {
                                compareSelection = if (index in compareSelection) {
                                    compareSelection - index
                                } else if (compareSelection.size < 2) {
                                    compareSelection + index
                                } else {
                                    compareSelection
                                }
                            } else {
                                selectedIndex = index
                                currentPage = index
                            }
                        },
                        onSelectForCompare = {
                            isSelectingForCompare = true
                            compareSelection = emptySet()
                        },
                        onCancelCompare = {
                            isSelectingForCompare = false
                            compareSelection = emptySet()
                        },
                        onCompare = {
                            val sorted = compareSelection.sorted()
                            compareIndices = Pair(sorted[0], sorted[1])
                            isSelectingForCompare = false
                            compareSelection = emptySet()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun ResultGridView(
    results: List<BenchmarkResult>,
    isSelectingForCompare: Boolean,
    compareSelection: Set<Int>,
    onItemClick: (Int) -> Unit,
    onSelectForCompare: () -> Unit,
    onCancelCompare: () -> Unit,
    onCompare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            itemsIndexed(results) { index, result ->
                val isSelected = index in compareSelection
                Card(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .then(
                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary,
                                MaterialTheme.shapes.medium)
                            else Modifier
                        )
                        .clickable { onItemClick(index) }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = result.imagePath,
                            contentDescription = result.title,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    rotationZ = result.displayRotationDegrees.toFloat()
                                },
                            contentScale = ContentScale.Crop
                        )
                        if (isSelectingForCompare) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onItemClick(index) },
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        ) {
                            ResultLabel(
                                result = result,
                                modifier = Modifier.padding(4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Bottom action bar
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                if (isSelectingForCompare) {
                    OutlinedButton(onClick = onCancelCompare) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onCompare,
                        enabled = compareSelection.size == 2
                    ) {
                        Text("Compare")
                    }
                } else {
                    OutlinedButton(onClick = onSelectForCompare) {
                        Text("Select for comparison")
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
    referenceIndex: Int = -1,
    onCompareWithReference: (Int) -> Unit = {},
    histograms: Map<String, RgbHistogram> = emptyMap(),
    showHistograms: Boolean = false,
    onToggleHistograms: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { results.size })
    var isPagerScrollEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
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
                    RotationCompensatedAsyncImage(
                        model = result.imagePath,
                        contentDescription = result.title,
                        rotationDegrees = result.displayRotationDegrees,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                    )

                    if (showHistograms) {
                        histograms[result.imagePath]?.let { histogram ->
                            HistogramOverlay(
                                histogram = histogram,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                            )
                        }
                    }

                    if (referenceIndex >= 0 && page != referenceIndex) {
                        IconButton(
                            onClick = { onCompareWithReference(page) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CompareArrows,
                                contentDescription = "Compare with reference",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            HistogramToolbar(
                showHistograms = showHistograms,
                onToggleHistograms = onToggleHistograms,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 4.dp)
            )
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
                    currentResult.inputFrameDescription()?.let { description ->
                        Text(
                            text = description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
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

private fun BenchmarkResult.inputFrameDescription(): String? {
    if (inputFrameIndices.isEmpty()) return null
    val frames = inputFrameIndices.joinToString(", ") { (it + 1).toString() }
    return "Input frames: $frames"
}

/**
 * AsyncImage wrapper that accounts for a display rotation of 90/270 degrees when applying
 * [contentScale]. A plain rotationZ transform rotates the already-scaled image in place, which
 * leaves letterboxing when the un-rotated bitmap's aspect ratio doesn't match the container.
 * Here the child is measured against swapped constraints so Fit/Crop scale against the bitmap's
 * post-rotation footprint, then the result is rotated back into the container's orientation.
 */
@Composable
private fun RotationCompensatedAsyncImage(
    model: Any?,
    contentDescription: String?,
    rotationDegrees: Int,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    val swapDimensions = normalizedRotation == 90 || normalizedRotation == 270
    Layout(
        content = {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        },
        modifier = modifier
    ) { measurables, constraints ->
        val childConstraints = if (swapDimensions) {
            Constraints.fixed(constraints.maxHeight, constraints.maxWidth)
        } else {
            Constraints.fixed(constraints.maxWidth, constraints.maxHeight)
        }
        val placeable = measurables.first().measure(childConstraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.placeWithLayer(
                x = (constraints.maxWidth - placeable.width) / 2,
                y = (constraints.maxHeight - placeable.height) / 2
            ) {
                rotationZ = normalizedRotation.toFloat()
            }
        }
    }
}

@Composable
private fun ResultLabel(
    result: BenchmarkResult,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (result.preprocessedFrameIndex != null && result.preprocessedFrameCount != null) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = "Frame ${result.preprocessedFrameIndex + 1} of ${result.preprocessedFrameCount}",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall
            )
        } else {
            Text(
                text = result.title,
                style = MaterialTheme.typography.labelSmall
            )
        }
        result.subtitle?.let { subtitle ->
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall
            )
        }
        result.inputFrameDescription()?.let { description ->
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun CompareView(
    resultA: BenchmarkResult,
    resultB: BenchmarkResult,
    isVertical: Boolean = true,
    histograms: Map<String, RgbHistogram> = emptyMap(),
    showHistograms: Boolean = false,
    onToggleHistograms: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(isVertical) {
        scale = 1f
        offset = Offset.Zero
    }

    Box(modifier = modifier.fillMaxSize()) {
        val gestureModifier = Modifier
            .fillMaxSize()
            .clipToBounds()
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
            .pointerInput(isVertical) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val changes = event.changes

                        if (changes.size >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale

                            if (newScale > 1f) {
                                val maxX = if (isVertical) {
                                    size.width * (newScale - 1f) / 2f
                                } else {
                                    size.width * (newScale - 1f) / 4f
                                }
                                val maxY = if (isVertical) {
                                    size.height * (newScale - 1f) / 4f
                                } else {
                                    size.height * (newScale - 1f) / 2f
                                }
                                offset = Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY)
                                )
                            } else {
                                offset = Offset.Zero
                            }
                            changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (changes.size == 1 && scale > 1.01f) {
                            val pan = event.calculatePan()
                            val maxX = if (isVertical) {
                                size.width * (scale - 1f) / 2f
                            } else {
                                size.width * (scale - 1f) / 4f
                            }
                            val maxY = if (isVertical) {
                                size.height * (scale - 1f) / 4f
                            } else {
                                size.height * (scale - 1f) / 2f
                            }
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                            changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (changes.any { it.pressed })
                }
            }

        if (isVertical) {
            Column(modifier = gestureModifier) {
                CompareImageBox(
                    result = resultA,
                    scale = scale,
                    offset = offset,
                    histogram = histograms[resultA.imagePath],
                    showHistogram = showHistograms,
                    histogramBottomPadding = 44.dp,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                CompareImageBox(
                    result = resultB,
                    scale = scale,
                    offset = offset,
                    histogram = histograms[resultB.imagePath],
                    showHistogram = showHistograms,
                    histogramBottomPadding = 44.dp,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        } else {
            Row(modifier = gestureModifier) {
                CompareImageBox(
                    result = resultA,
                    scale = scale,
                    offset = offset,
                    histogram = histograms[resultA.imagePath],
                    showHistogram = showHistograms,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                CompareImageBox(
                    result = resultB,
                    scale = scale,
                    offset = offset,
                    histogram = histograms[resultB.imagePath],
                    showHistogram = showHistograms,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }

        HistogramToolbar(
            showHistograms = showHistograms,
            onToggleHistograms = onToggleHistograms,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 4.dp)
        )

    }
}

@Composable
private fun CompareImageBox(
    result: BenchmarkResult,
    scale: Float,
    offset: Offset,
    histogram: RgbHistogram?,
    showHistogram: Boolean,
    histogramBottomPadding: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.clipToBounds()
    ) {
        RotationCompensatedAsyncImage(
            model = result.imagePath,
            contentDescription = result.title,
            rotationDegrees = result.displayRotationDegrees,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
        if (showHistogram) {
            histogram?.let {
                HistogramOverlay(
                    histogram = it,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = histogramBottomPadding)
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ResultLabel(
                result = result,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}
