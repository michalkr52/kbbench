package com.kbbench.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kbbench.app.ui.components.AlgorithmSelectorDialog
import com.kbbench.app.ui.components.CameraPreview
import com.kbbench.app.viewmodel.AppScreen
import com.kbbench.app.viewmodel.CameraViewModel
import kotlinx.coroutines.delay

@Composable
fun MainScreen(viewModel: CameraViewModel = viewModel()) {
    val currentScreen by viewModel.currentScreen.collectAsState()

    when (currentScreen) {
        AppScreen.CAMERA -> CameraScreen(viewModel)
        AppScreen.RESULTS -> ResultScreen(viewModel)
    }
}

@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val captureFormat by viewModel.captureFormat.collectAsState()
    val zoomLevel by viewModel.zoomLevel.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val enabledAlgorithmNames by viewModel.enabledAlgorithmNames.collectAsState()
    val algorithmParameters by viewModel.algorithmParameters.collectAsState()
    var lastSurface by remember { mutableStateOf<android.view.Surface?>(null) }
    var focusTapPosition by remember { mutableStateOf<Offset?>(null) }
    var showFocusIndicator by remember { mutableStateOf(false) }
    var showAlgorithmSelector by remember { mutableStateOf(false) }

    val uploadInputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let { viewModel.loadInputFromGallery(context, it) } }
    )

    // Hide focus indicator after a short delay
    LaunchedEffect(focusTapPosition) {
        if (focusTapPosition != null) {
            showFocusIndicator = true
            delay(1000)
            showFocusIndicator = false
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(key1 = hasCameraPermission) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        if (hasCameraPermission) {
            val isRaw = captureFormat == ImageFormat.RAW_SENSOR
            val previewSize by viewModel.previewSize.collectAsState()
            val controlsEnabled = !isProcessing

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Camera preview / loaded image, aligned to the top and sized to its own aspect ratio
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { offset ->
                                    if (controlsEnabled) {
                                        focusTapPosition = offset
                                        viewModel.focusAt(offset.x, offset.y, size.width, size.height)
                                    }
                                }
                            )
                        }
                        .then(
                            if (!isRaw) {
                                Modifier.pointerInput(Unit) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        if (controlsEnabled) {
                                            viewModel.setZoom(zoom)
                                        }
                                    }
                                }
                            } else Modifier
                        )
                ) {
                    CameraPreview(
                        modifier = Modifier.fillMaxWidth(),
                        previewWidth = previewSize?.width ?: 0,
                        previewHeight = previewSize?.height ?: 0,
                        onSurfaceCreated = { surface ->
                            lastSurface = surface
                            viewModel.initialize(context)
                            viewModel.startPreview(context, surface)
                        }
                    )

                    // Focus indicator
                    val focusPos = focusTapPosition
                    if (focusPos != null) {
                        val indicatorSizeDp = 48.dp
                        val indicatorSizePx = with(LocalDensity.current) { indicatorSizeDp.toPx() }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showFocusIndicator,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (focusPos.x - indicatorSizePx / 2).toInt(),
                                            (focusPos.y - indicatorSizePx / 2).toInt()
                                        )
                                    }
                                    .size(indicatorSizeDp)
                                    .border(2.dp, Color.White)
                            )
                        }
                    }

                    // Zoom Level Indicator
                    if (!isRaw && zoomLevel > 1f) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .padding(top = 16.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.5f),
                                shape = CircleShape
                            ) {
                                Text(
                                    text = "%.1fx".format(zoomLevel),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                    // Spinning loader shown while a captured/loaded image is being processed
                    Box(
                        modifier = Modifier.matchParentSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isProcessing) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(MaterialTheme.colorScheme.background.copy(alpha=0.5f))
                            )
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = isProcessing,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.5f),
                                shape = CircleShape
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .size(40.dp),
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // Controls occupy the remaining space below the image and are centered within it
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(
                            modifier = Modifier.padding(bottom = 32.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { showAlgorithmSelector = true },
                                enabled = !isProcessing,
                                modifier = Modifier
                                    .padding(end = 24.dp)
                                    .size(56.dp)
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Tune,
                                    contentDescription = "Select algorithms",
                                    tint = Color.White
                                )
                            }

                            IconButton(
                                onClick = {
                                    uploadInputLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                enabled = !isProcessing,
                                modifier = Modifier
                                    .padding(end = 24.dp)
                                    .size(56.dp)
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PhotoLibrary,
                                    contentDescription = "Load input image",
                                    tint = Color.White
                                )
                            }

                            Button(
                                onClick = { viewModel.takePhoto(context) },
                                modifier = Modifier.size(80.dp),
                                shape = CircleShape,
                                enabled = !isProcessing
                            ) {
                                // Empty content for now
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Capture format",
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                val formats = listOf(
                                    "JPEG" to ImageFormat.JPEG,
                                    "RAW" to ImageFormat.RAW_SENSOR
                                )
                                formats.forEach { (name, format) ->
                                    FilterChip(
                                        selected = captureFormat == format,
                                        onClick = {
                                            if (controlsEnabled) {
                                                viewModel.setCaptureFormat(format)
                                                lastSurface?.let { viewModel.startPreview(context, it) }
                                            }
                                        },
                                        enabled = controlsEnabled,
                                        label = { Text(name) },
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                            }

                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Camera permission required")
            }
        }
    }

    if (showAlgorithmSelector) {
        AlgorithmSelectorDialog(
            algorithms = viewModel.availableAlgorithms.map { it.metadata },
            enabledAlgorithmNames = enabledAlgorithmNames,
            parameterValues = algorithmParameters,
            onAlgorithmEnabledChanged = viewModel::setAlgorithmEnabled,
            onAllAlgorithmsEnabledChanged = viewModel::setAllAlgorithmsEnabled,
            onParameterChanged = viewModel::setAlgorithmParameter,
            onParameterCommit = viewModel::commitAlgorithmParameters,
            onResetParameters = viewModel::resetAlgorithmParameters,
            confirmLabel = "Done",
            onConfirm = { showAlgorithmSelector = false },
            onDismiss = { showAlgorithmSelector = false }
        )
    }
}
