package com.kbbench.app.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import android.media.ImageReader
import android.net.Uri
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.OrientationEventListener
import android.view.OrientationEventListener.ORIENTATION_UNKNOWN
import android.view.Surface
import android.webkit.MimeTypeMap
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kbbench.algorithm.base.*
import com.kbbench.algorithm.impl.AlgorithmRegistry
import com.kbbench.app.settings.BenchmarkSettingsStore
import com.kbbench.algorithm.preprocessing.BayerDemosaic
import com.kbbench.algorithm.preprocessing.CfaPattern
import com.kbbench.algorithm.preprocessing.LensShadingCorrection
import com.kbbench.algorithm.preprocessing.Raw16Decoder
import com.kbbench.algorithm.preprocessing.ShadingMap
import com.kbbench.utils.RgbHistogram
import com.kbbench.utils.calculateRgbHistogram
import com.kbbench.utils.centerCropAndScale
import com.kbbench.utils.getPreviewOutputSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

enum class AppScreen {
    CAMERA,
    RESULTS
}

data class BenchmarkMetrics(
    val runtimeMs: Long? = null,
    val psnr: Double? = null,
    val ssim: Double? = null,
) {
    /**
     * Converts metrics into a display-friendly list of key-value pairs.
     */
    fun toDisplayList(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        runtimeMs?.let { list.add("Runtime" to "${it}ms") }
        psnr?.let { list.add("PSNR" to "%.2f dB".format(Locale.US, it)) }
        ssim?.let { list.add("SSIM" to "%.4f".format(Locale.US, it)) }
        return list
    }
}

data class BenchmarkResult(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val imagePath: String,
    val displayRotationDegrees: Int = 0,
    val preprocessedFrameIndex: Int? = null,
    val preprocessedFrameCount: Int? = null,
    val inputFrameIndices: List<Int> = emptyList(),
    val metrics: BenchmarkMetrics = BenchmarkMetrics()
)

/** A user-supplied ground-truth image to score algorithm outputs against. */
data class ReferenceImage(val bitmap: Bitmap, val displayPath: String)

/** Locates one algorithm run's persisted PNG so it can be rescored without re-running the algorithm. */
private data class AlgorithmOutputRecord(
    val id: String,
    val imagePath: String,
    val runtimeMs: Long,
    val parameters: Map<String, Double> = emptyMap(),
)

private data class CaptureMetadata(
    val sourceFormat: String,
    val width: Int,
    val height: Int,
    val exposureTimesMs: List<Double>,
    val isoValues: List<Int>,
    val captureTimeMs: Long,
    val rawWhiteLevel: Int? = null,
    val rawBlackLevel: Int? = null,
    val cfaPattern: Int? = null,
    val whiteBalanceGains: List<Float>? = null,
)

private data class ExportImageArtifact(
    val id: String,
    val path: String,
    val role: String,
    val width: Int,
    val height: Int,
    val frameIndex: Int? = null,
)

private data class PersistedPickedImage(
    val file: File,
    val sourceFormat: String,
)

/** Everything needed to re-execute algorithms on an already prepared capture. */
private data class LastRunInput(
    val originalFilePath: String,
    val originalDisplayRotation: Int,
    val preprocessedPaths: List<String>,
    val width: Int,
    val height: Int,
    val exposureTimes: List<Long>,
    val isoValues: List<Int>,
    val captureTimeMs: Long,
    val captureMetadata: CaptureMetadata,
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = BenchmarkSettingsStore(application)

    /** Temporary profiling helper: logs wall-clock time of [block] under the "Perf" tag. */
    private inline fun <T> logTimed(label: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        val result = block()
        Log.d("Perf", "$label: ${System.currentTimeMillis() - start} ms")
        return result
    }

    /** Suspend variant of [logTimed] for blocks that call suspend functions. */
    private suspend inline fun <T> logTimedSuspend(label: String, crossinline block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        val result = block()
        Log.d("Perf", "$label: ${System.currentTimeMillis() - start} ms")
        return result
    }

    private fun logMemory(label: String) {
        val runtime = Runtime.getRuntime()
        val usedHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxHeapMb = runtime.maxMemory() / (1024 * 1024)
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        Log.d("Perf", "$label memory: heap=${usedHeapMb}MB/${maxHeapMb}MB native=${nativeHeapMb}MB")
    }

    private val algorithmRegistry = AlgorithmRegistry()
    val availableAlgorithms: List<ImageAlgorithm> = algorithmRegistry.getAll()
    private val availableAlgorithmNames: Set<String> = availableAlgorithms.map { it.name }.toSet()

    private val _enabledAlgorithmNames = MutableStateFlow(loadEnabledAlgorithmNames())
    val enabledAlgorithmNames = _enabledAlgorithmNames.asStateFlow()

    /** Non-default parameter overrides: algorithm name -> parameter id -> value. */
    private val _algorithmParameters = MutableStateFlow(loadAlgorithmParameters())
    val algorithmParameters = _algorithmParameters.asStateFlow()

    private fun loadEnabledAlgorithmNames(): Set<String> {
        val saved = settingsStore.loadEnabledAlgorithms()?.intersect(availableAlgorithmNames)
        return if (saved.isNullOrEmpty()) availableAlgorithmNames else saved
    }

    private fun loadAlgorithmParameters(): Map<String, Map<String, Double>> =
        settingsStore.loadAlgorithmParameters()
            .filterKeys { it in availableAlgorithmNames }
            .mapValues { (algorithmName, values) ->
                val declared = algorithmRegistry.getByName(algorithmName).metadata.parameters
                values.filterKeys { id -> declared.any { it.id == id } }
            }
            .filterValues { it.isNotEmpty() }

    fun setAlgorithmEnabled(name: String, enabled: Boolean) {
        if (name !in availableAlgorithmNames) return
        _enabledAlgorithmNames.value = if (enabled) {
            _enabledAlgorithmNames.value + name
        } else {
            _enabledAlgorithmNames.value - name
        }
        settingsStore.saveEnabledAlgorithms(_enabledAlgorithmNames.value)
    }

    fun setAllAlgorithmsEnabled(enabled: Boolean) {
        _enabledAlgorithmNames.value = if (enabled) availableAlgorithmNames else emptySet()
        settingsStore.saveEnabledAlgorithms(_enabledAlgorithmNames.value)
    }

    /**
     * Updates one tuning value in memory. Persisting is deferred to [commitAlgorithmParameters] so a
     * slider drag does not write once per frame.
     */
    fun setAlgorithmParameter(algorithmName: String, parameterId: String, value: Double) {
        val declared = availableAlgorithms.find { it.name == algorithmName }
            ?.metadata?.parameters?.firstOrNull { it.id == parameterId } ?: return
        val clamped = value.coerceIn(declared.min, declared.max)
        val current = _algorithmParameters.value
        val updated = current[algorithmName].orEmpty() + (parameterId to clamped)
        _algorithmParameters.value = current + (algorithmName to updated)
    }

    fun commitAlgorithmParameters() {
        settingsStore.saveAlgorithmParameters(_algorithmParameters.value)
    }

    fun resetAlgorithmParameters(algorithmName: String) {
        _algorithmParameters.value = _algorithmParameters.value - algorithmName
        commitAlgorithmParameters()
    }

    /** Effective tuning values for [algorithm]: declared defaults with the user's overrides applied. */
    private fun effectiveParameters(algorithm: ImageAlgorithm): Map<String, Double> =
        algorithm.metadata.parameters.effectiveValues(
            _algorithmParameters.value[algorithm.name].orEmpty()
        )

    private fun getEnabledAlgorithms(): List<ImageAlgorithm> =
        availableAlgorithms.filter { it.name in _enabledAlgorithmNames.value }

    private val _currentScreen = MutableStateFlow(AppScreen.CAMERA)
    val currentScreen = _currentScreen.asStateFlow()

    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady = _isCameraReady.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    private val _captureFormat = MutableStateFlow(ImageFormat.JPEG)
    val captureFormat = _captureFormat.asStateFlow()

    private val _referenceImage = MutableStateFlow<ReferenceImage?>(null)
    val referenceImage = _referenceImage.asStateFlow()

    private var lastAlgorithmOutputs: List<AlgorithmOutputRecord> = emptyList()

    private var lastRunInput: LastRunInput? = null

    private val _canRerun = MutableStateFlow(false)
    val canRerun = _canRerun.asStateFlow()

    private val _benchmarkResults = MutableStateFlow<List<BenchmarkResult>>(emptyList())
    val benchmarkResults = _benchmarkResults.asStateFlow()

    private val _histograms = MutableStateFlow<Map<String, RgbHistogram>>(emptyMap())
    val histograms = _histograms.asStateFlow()

    private var exportImageArtifacts: List<ExportImageArtifact> = emptyList()
    private var captureMetadata = CaptureMetadata(
        sourceFormat = "unknown",
        width = 0,
        height = 0,
        exposureTimesMs = emptyList(),
        isoValues = emptyList(),
        captureTimeMs = 0L,
    )

    private var histogramJob: Job? = null
    private var histogramGeneration = 0L

    private val _zoomLevel = MutableStateFlow(1f)
    val zoomLevel = _zoomLevel.asStateFlow()

    private val _previewSize = MutableStateFlow<android.util.Size?>(null)
    val previewSize = _previewSize.asStateFlow()

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null
    private var hasInitializedCaptureFormat = false

    private var orientationEventListener: OrientationEventListener? = null
    private var deviceOrientation = 0 // 0, 90, 180, 270

    private var hasPurgedOrphanedFiles = false

    fun setCaptureFormat(format: Int) {
        _captureFormat.value = format
        settingsStore.saveCaptureFormat(format)
        if (format == ImageFormat.RAW_SENSOR) {
            _zoomLevel.value = 1f
        }
    }

    /**
     * Removes persisted capture/preprocessed/output files left behind by previous process
     * instances (e.g. after a crash or process death), since no in-memory state can reference
     * them once the app cold-starts. Runs once per process lifetime.
     */
    private fun purgeOrphanedFilesOnce(context: Context) {
        if (hasPurgedOrphanedFiles) return
        hasPurgedOrphanedFiles = true
        viewModelScope.launch(Dispatchers.IO) {
            val prefixes = listOf("IMG_", "REF_", "PREPROCESSED_", "OUT_")
            val orphaned = context.filesDir.listFiles { file ->
                prefixes.any { file.name.startsWith(it) }
            } ?: return@launch
            deleteFiles(orphaned.map { it.absolutePath })
        }
    }

    fun initialize(context: Context) {
        purgeOrphanedFilesOnce(context)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()

        cameraId?.let {
            characteristics = manager.getCameraCharacteristics(it)
        }

        if (!hasInitializedCaptureFormat) {
            val capabilities = characteristics?.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )
            val supportsRaw =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in (capabilities ?: intArrayOf())
            val saved = settingsStore.loadCaptureFormat()
            _captureFormat.value = when {
                saved == ImageFormat.RAW_SENSOR && supportsRaw -> ImageFormat.RAW_SENSOR
                saved == ImageFormat.JPEG -> ImageFormat.JPEG
                supportsRaw -> ImageFormat.RAW_SENSOR
                else -> ImageFormat.JPEG
            }
            hasInitializedCaptureFormat = true
        }

        if (orientationEventListener == null) {
            orientationEventListener = object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    deviceOrientation = when {
                        orientation <= 45 || orientation > 315 -> 0
                        orientation <= 135 -> 90
                        orientation <= 225 -> 180
                        else -> 270
                    }
                }
            }
            orientationEventListener?.enable()
        }
    }

    private fun computeRelativeRotation(chars: CameraCharacteristics): Int {
        val sensorOrientationDegrees = chars[CameraCharacteristics.SENSOR_ORIENTATION]!!

        // Reverse device orientation for front-facing cameras
        val sign = if (chars[CameraCharacteristics.LENS_FACING] ==
                CameraCharacteristics.LENS_FACING_FRONT) 1 else -1

        // Calculate desired JPEG orientation relative to camera orientation to make
        // the image upright relative to the device orientation
        return (sensorOrientationDegrees - (deviceOrientation * sign) + 360) % 360
    }

    @SuppressLint("MissingPermission")
    fun startPreview(context: Context, surface: Surface) {
        val id = cameraId ?: return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = characteristics ?: manager.getCameraCharacteristics(id).also { characteristics = it }
        previewSurface = surface

        viewModelScope.launch(Dispatchers.Main) {
            try {
                // Close existing session and reader if any
                session?.close()
                imageReader?.close()
                camera?.close()

                camera = openCamera(manager, id, cameraHandler)

                val currentFormat = _captureFormat.value
                val size = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                    .getOutputSizes(currentFormat).maxByOrNull { it.height * it.width }!!

                _previewSize.value = size
                val maxFrames = algorithmRegistry.getAll()
                    .maxOfOrNull { it.metadata.frameRequirements.minFrames } ?: 1
                val readerBufferSize = maxFrames.coerceAtLeast(3)
                imageReader = ImageReader.newInstance(size.width, size.height, currentFormat, readerBufferSize)

                val targets = listOf(surface, imageReader!!.surface)
                session = createCaptureSession(camera!!, targets, cameraHandler)

                updatePreview()
                _isCameraReady.value = true
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error starting preview", e)
            }
        }
    }

    private fun updatePreview() {
        val cam = camera ?: return
        val sess = session ?: return
        val surface = previewSurface ?: return
        val chars = characteristics ?: return

        try {
            val captureRequest = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                if (_captureFormat.value != ImageFormat.RAW_SENSOR) {
                    applyZoom(this, _zoomLevel.value, chars)
                }
            }
            sess.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Error updating preview", e)
        }
    }

    fun setZoom(scale: Float) {
        val chars = characteristics ?: return
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

        val newZoom = (_zoomLevel.value * scale).coerceIn(1f, maxZoom)
        if (newZoom != _zoomLevel.value) {
            _zoomLevel.value = newZoom
            updatePreview()
        }
    }

    fun focusAt(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val chars = characteristics ?: return
        val sess = session ?: return
        val cam = camera ?: return
        val surface = previewSurface ?: return
        val activeArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        // Map tap coordinates to the current crop region (accounts for zoom)
        val effectiveZoom = if (_captureFormat.value == ImageFormat.RAW_SENSOR) 1f else _zoomLevel.value
        val cropWidth = activeArraySize.width() / effectiveZoom
        val cropHeight = activeArraySize.height() / effectiveZoom
        val cropLeft = activeArraySize.centerX() - cropWidth / 2f
        val cropTop = activeArraySize.centerY() - cropHeight / 2f

        val sensorX = (cropLeft + x / viewWidth * cropWidth).toInt()
        val sensorY = (cropTop + y / viewHeight * cropHeight).toInt()
        val meteringSize = (cropWidth * 0.05f).toInt()

        val meteringRect = android.graphics.Rect(
            (sensorX - meteringSize).coerceAtLeast(0),
            (sensorY - meteringSize).coerceAtLeast(0),
            (sensorX + meteringSize).coerceAtMost(activeArraySize.width()),
            (sensorY + meteringSize).coerceAtMost(activeArraySize.height())
        )
        val meteringRectangle = MeteringRectangle(meteringRect, MeteringRectangle.METERING_WEIGHT_MAX)

        try {
            // Set repeating request with AF_MODE_AUTO and regions so focus is maintained
            val repeatingRequest = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRectangle))
                set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRectangle))
                if (_captureFormat.value != ImageFormat.RAW_SENSOR) {
                    applyZoom(this, _zoomLevel.value, chars)
                }
            }
            sess.setRepeatingRequest(repeatingRequest.build(), null, cameraHandler)

            // Send one-shot AF trigger on top of the repeating request
            val triggerRequest = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRectangle))
                set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRectangle))
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                if (_captureFormat.value != ImageFormat.RAW_SENSOR) {
                    applyZoom(this, _zoomLevel.value, chars)
                }
            }
            sess.capture(triggerRequest.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Error focusing", e)
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder, zoomLevel: Float, chars: CameraCharacteristics) {
        val activeArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val centerX = activeArraySize.centerX()
        val centerY = activeArraySize.centerY()
        val deltaX = (activeArraySize.width() / (2f * zoomLevel)).toInt()
        val deltaY = (activeArraySize.height() / (2f * zoomLevel)).toInt()

        val cropRegion = android.graphics.Rect(
            centerX - deltaX,
            centerY - deltaY,
            centerX + deltaX,
            centerY + deltaY
        )
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
    }

    fun takePhoto(context: Context) {
        val sess = session ?: return
        val reader = imageReader ?: return
        val id = cameraId ?: return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = characteristics ?: manager.getCameraCharacteristics(id)

        viewModelScope.launch(Dispatchers.IO) {
            _isProcessing.value = true
            try {
                val isRaw = _captureFormat.value == ImageFormat.RAW_SENSOR
                val algorithms = getEnabledAlgorithms()
                val maxFramesNeeded = algorithms.maxOfOrNull { it.metadata.frameRequirements.minFrames } ?: 1

                val request = sess.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    if (!isRaw) {
                        applyZoom(this, _zoomLevel.value, chars)
                    } else {
                        // RAW_SENSOR is never shading-corrected by the device, so we need the map ourselves.
                        set(
                            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                        )
                    }
                    set(CaptureRequest.JPEG_ORIENTATION, computeRelativeRotation(chars))
                }

                // Determine if exposure bracketing is needed
                val needsBracketing = algorithms.any {
                    it.metadata.frameRequirements.inputFrameType == InputFrameType.EXPOSURE_BRACKET
                }

                // Capture frames (with bracketing if needed)
                val captureStartMs = System.currentTimeMillis()
                val capturedFrames = if (needsBracketing && maxFramesNeeded > 1) {
                    captureBracketedFrames(sess, reader, request, chars, maxFramesNeeded)
                } else {
                    captureFrames(sess, reader, request, maxFramesNeeded)
                }
                val captureTimeMs = System.currentTimeMillis() - captureStartMs
                Log.d("Perf", "camera capture x${capturedFrames.size}: $captureTimeMs ms")

                // Save first frame as the original reference
                val firstResult = capturedFrames.first()
                val file = logTimed("saveResult (original)") { saveResult(context, firstResult, chars) }

                // Fix orientation for JPEG
                if (!isRaw) {
                    try {
                        val relativeRotation = computeRelativeRotation(chars)
                        val mirrored = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = com.kbbench.utils.computeExifOrientation(relativeRotation, mirrored)

                        val exif = ExifInterface(file.absolutePath)
                        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                        exif.saveAttributes()
                    } catch (e: Exception) {
                        Log.e("CameraViewModel", "Error saving metadata", e)
                    }
                }

                // Get RAW white/black levels from characteristics for proper normalization
                val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                val blackLevel = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let {
                    // Average of 4 Bayer channels
                    ((it.getOffsetForIndex(0, 0) + it.getOffsetForIndex(0, 1) +
                      it.getOffsetForIndex(1, 0) + it.getOffsetForIndex(1, 1)) / 4)
                } ?: 64
                val cfaPattern = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                    ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB

                // Colour rendering is taken once from the first frame: bracketed frames keep auto WB,
                // so per-frame gains would drift and fuse into colour artifacts.
                val referenceMetadata = capturedFrames.first().metadata
                val wbGains = referenceMetadata.get(CaptureResult.COLOR_CORRECTION_GAINS)
                val colorMatrix = if (isRaw) cameraToSrgbMatrix(chars, referenceMetadata) else null
                val shadingMap = if (isRaw) lensShadingMap(referenceMetadata) else null

                // Convert captured frames to display-oriented ARGB pixels for algorithms
                val rotation = computeRelativeRotation(chars)
                val decodedFrames = logTimed("decodeToArgb x${capturedFrames.size}") {
                    capturedFrames.map { frame ->
                        decodeToArgb(
                            frame, whiteLevel, blackLevel, cfaPattern,
                            wbGains, colorMatrix, shadingMap
                        )
                    }
                }
                val framePixels = logTimed("rotateArgb x${decodedFrames.size}") {
                    decodedFrames.map { (pixels, frameWidth, frameHeight) ->
                        rotateArgb(pixels, frameWidth, frameHeight, rotation)
                    }
                }
                val width = framePixels.first().second
                val height = framePixels.first().third
                val pixelArrays = framePixels.map { it.first }

                // Extract exposure metadata from capture results
                val exposureTimes = capturedFrames.map { frame ->
                    frame.metadata.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                }
                val isoValues = capturedFrames.map { frame ->
                    frame.metadata.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                }

                // Close all captured images
                capturedFrames.forEach { it.image.close() }

                // Run algorithms and build results
                logTimedSuspend("runBenchmarks") {
                    runBenchmarks(context, file, algorithms, pixelArrays, width, height,
                        exposureTimes, isoValues, captureTimeMs, if (isRaw) rotation else 0,
                        CaptureMetadata(
                            sourceFormat = if (isRaw) "RAW_SENSOR" else "JPEG",
                            width = width,
                            height = height,
                            exposureTimesMs = exposureTimes.map { it / 1_000_000.0 },
                            isoValues = isoValues,
                            captureTimeMs = captureTimeMs,
                            rawWhiteLevel = if (isRaw) whiteLevel else null,
                            rawBlackLevel = if (isRaw) blackLevel else null,
                            cfaPattern = if (isRaw) cfaPattern else null,
                            whiteBalanceGains = wbGains
                                ?.let { listOf(it.red, it.greenEven, it.blue) }
                        ))
                }
                logTimed("recomputeMetricsWithReference") { recomputeMetricsWithReference() }

                _currentScreen.value = AppScreen.RESULTS
                closeCamera()
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error taking photo", e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /** Bradford-adapted CIE XYZ (D50) to linear sRGB, the white point DNG forward matrices target. */
    private val xyzD50ToSrgb = floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f, 1.9161415f, 0.0334540f,
        0.0719453f, -0.2289914f, 1.4052427f
    )

    /**
     * Row-major 3x3 transform from white-balanced camera RGB to linear sRGB.
     *
     * Prefers the DNG forward matrix from [CameraCharacteristics], which is static factory
     * calibration mandatory on RAW-capable devices and is what a DNG renderer uses. The
     * per-capture COLOR_CORRECTION_TRANSFORM is only a fallback: devices without
     * MANUAL_POST_PROCESSING often report it as null or unity while AWB is on auto.
     */
    private fun cameraToSrgbMatrix(
        chars: CameraCharacteristics,
        metadata: CaptureResult
    ): FloatArray? {
        val forward = selectForwardMatrix(chars)
        if (forward != null) {
            val matrix = multiply3x3(xyzD50ToSrgb, forward)
            Log.d("CameraViewModel", "Camera->sRGB from forward matrix: ${matrix.joinToString()}")
            return matrix
        }

        val transform = metadata.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { toRowMajor(it) }
        if (transform == null) {
            Log.w("CameraViewModel", "No colour calibration available; RAW colours stay camera-native")
        }
        return transform
    }

    private fun selectForwardMatrix(chars: CameraCharacteristics): FloatArray? {
        val first = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
        val second = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)
        val secondIsD65 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ==
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D65
        val chosen = if (secondIsD65) second ?: first else first ?: second
        return chosen?.let { toRowMajor(it) }
    }

    private fun toRowMajor(transform: android.hardware.camera2.params.ColorSpaceTransform): FloatArray =
        FloatArray(9) { i -> transform.getElement(i % 3, i / 3).toFloat() }

    private fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray =
        FloatArray(9) { i ->
            val row = i / 3
            val col = i % 3
            a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
        }

    private fun lensShadingMap(metadata: CaptureResult): ShadingMap? {
        val map = metadata.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        if (map == null) {
            Log.w("CameraViewModel", "No lens shading map; RAW keeps the lens' corner falloff")
            return null
        }
        val gains = FloatArray(map.rowCount * map.columnCount * 4)
        map.copyGainFactors(gains, 0)
        return ShadingMap(gains, map.columnCount, map.rowCount)
    }

    private fun decodeToArgb(
        frame: CombinedResult,
        whiteLevel: Int = 1023,
        blackLevel: Int = 64,
        cfaPattern: Int = CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
        whiteBalanceGains: android.hardware.camera2.params.RggbChannelVector? = null,
        colorMatrix: FloatArray? = null,
        shadingMap: ShadingMap? = null
    ): Triple<IntArray, Int, Int> {        val image = frame.image
        return if (image.format == ImageFormat.JPEG) {
            val buffer = image.planes[0].buffer
            buffer.rewind()
            val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val w = bitmap.width
            val h = bitmap.height
            bitmap.recycle()
            Triple(pixels, w, h)
        } else {
            // RAW_SENSOR: normalize + Bayer demosaic via preprocessing module
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val raw = Raw16Decoder.normalizeRaw16(
                rawBuffer = plane.buffer,
                width = w,
                height = h,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                whiteLevel = whiteLevel,
                blackLevel = blackLevel
            )
            val bayerPattern = CfaPattern.fromId(cfaPattern)
            if (shadingMap != null) {
                LensShadingCorrection.applyInPlace(raw, w, h, bayerPattern, shadingMap)
            }
            // White balance gains and the colour transform are applied inside demosaic (float domain)
            // to avoid re-quantizing already-8-bit values, which produced comb-like histogram spikes.
            // Both are supplied by the caller so every frame of a bracket shares one colour space.
            val pixels = BayerDemosaic.demosaic(
                raw, w, h, bayerPattern,
                rGain = whiteBalanceGains?.red ?: 1f,
                gGain = whiteBalanceGains?.greenEven ?: 1f,
                bGain = whiteBalanceGains?.blue ?: 1f,
                colorMatrix = colorMatrix
            )

            Triple(pixels, w, h)
        }
    }

    private fun rotateArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ): Triple<IntArray, Int, Int> {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (normalizedRotation == 0) return Triple(pixels, width, height)

        val rotated = when (normalizedRotation) {
            90 -> IntArray(pixels.size).also { output ->
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        output[x * height + (height - 1 - y)] = pixels[y * width + x]
                    }
                }
            }
            180 -> IntArray(pixels.size).also { output ->
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        output[(height - 1 - y) * width + (width - 1 - x)] = pixels[y * width + x]
                    }
                }
            }
            270 -> IntArray(pixels.size).also { output ->
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        output[(width - 1 - x) * height + y] = pixels[y * width + x]
                    }
                }
            }
            else -> error("Unsupported rotation: $rotationDegrees")
        }
        val rotatedWidth = if (normalizedRotation == 90 || normalizedRotation == 270) height else width
        val rotatedHeight = if (normalizedRotation == 90 || normalizedRotation == 270) width else height
        return Triple(rotated, rotatedWidth, rotatedHeight)
    }

    private suspend fun captureFrames(
        session: CameraCaptureSession,
        reader: ImageReader,
        request: CaptureRequest.Builder,
        frameCount: Int
    ): List<CombinedResult> {
        // Flush any stale images
        while (true) {
            val old = reader.acquireNextImage() ?: break
            old.close()
        }

        val results = mutableListOf<CombinedResult>()
        for (i in 0 until frameCount) {
            results.add(captureSingleFrame(session, reader, request))
        }
        return results
    }

    private suspend fun captureBracketedFrames(
        session: CameraCaptureSession,
        reader: ImageReader,
        baseRequest: CaptureRequest.Builder,
        chars: CameraCharacteristics,
        frameCount: Int
    ): List<CombinedResult> {
        // Flush any stale images
        while (true) {
            val old = reader.acquireNextImage() ?: break
            old.close()
        }

        // First capture with auto-exposure to get the base exposure time
        val firstFrame = captureSingleFrame(session, reader, baseRequest)
        val baseExposure = firstFrame.metadata.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 33_000_000L
        val baseIso = firstFrame.metadata.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100

        val results = mutableListOf(firstFrame)

        // Exposure multipliers for bracketing (spread around the base exposure)
        // For 3 frames: [1x (already captured), 0.25x, 4x]
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val multipliers = when {
            frameCount >= 3 -> listOf(0.25, 4.0)
            frameCount == 2 -> listOf(4.0)
            else -> emptyList()
        }

        for (mult in multipliers) {
            val targetExposure = (baseExposure * mult).toLong().let { target ->
                if (exposureRange != null) {
                    target.coerceIn(exposureRange.lower, exposureRange.upper)
                } else target
            }

            baseRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            baseRequest.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetExposure)
            baseRequest.set(CaptureRequest.SENSOR_SENSITIVITY, baseIso)

            results.add(captureSingleFrame(session, reader, baseRequest))
        }

        return results
    }

    private suspend fun captureSingleFrame(
        session: CameraCaptureSession,
        reader: ImageReader,
        request: CaptureRequest.Builder
    ): CombinedResult = suspendCoroutine { cont ->
        val imageQueue = ArrayBlockingQueue<android.media.Image>(2)

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage()
            if (image != null) imageQueue.add(image)
        }, imageReaderHandler)

        session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                val image = imageQueue.take()
                cont.resume(CombinedResult(image, result))
            }
            override fun onCaptureFailed(s: CameraCaptureSession, req: CaptureRequest, failure: CaptureFailure) {
                cont.resumeWithException(RuntimeException("Capture failed: ${failure.reason}"))
            }
        }, cameraHandler)
    }

    private suspend fun runBenchmarks(
        context: Context,
        originalFile: File,
        algorithms: List<ImageAlgorithm>,
        frames: List<IntArray>,
        width: Int,
        height: Int,
        exposureTimes: List<Long>,
        isoValues: List<Int>,
        captureTimeMs: Long,
        originalDisplayRotation: Int,
        captureMetadata: CaptureMetadata,
        reusePreprocessedPaths: List<String>? = null
    ) = coroutineScope {
        Log.d("CameraViewModel", "Running ${algorithms.size} algorithms on ${frames.size} frame(s)")

        val results = mutableListOf<BenchmarkResult>()
        val outputRecords = mutableListOf<AlgorithmOutputRecord>()
        val artifacts = mutableListOf<ExportImageArtifact>()
        val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US).format(Date())
        val saveJobs = mutableListOf<Deferred<Unit>>()
        logMemory("benchmark start")

        // A re-run points at the PNGs the first run already wrote: re-encoding them costs seconds
        // per frame at full sensor resolution and would produce byte-identical files.
        val preprocessedFiles = frames.indices.map { index ->
            val preprocessedFile = reusePreprocessedPaths?.getOrNull(index)?.let { File(it) }
                ?: File(context.filesDir, "PREPROCESSED_${timestamp}_$index.png")
            artifacts.add(
                ExportImageArtifact(
                    id = "image_preprocessed_$index",
                    path = preprocessedFile.absolutePath,
                    role = "preprocessed_input",
                    width = width,
                    height = height,
                    frameIndex = index,
                )
            )
            preprocessedFile
        }

        this@CameraViewModel.captureMetadata = captureMetadata

        val isRawSource = captureMetadata.sourceFormat == "RAW_SENSOR"

        artifacts.add(
            ExportImageArtifact(
                id = "image_original",
                path = originalFile.absolutePath,
                role = "original_capture",
                width = width,
                height = height,
            )
        )
        // Original capture leads the Input group chronologically; preprocessed frame(s) follow it.
        results.add(
            BenchmarkResult(
                id = "original",
                title = if (isRawSource) "RAW Source (DNG)" else "Camera JPEG",
                subtitle = if (isRawSource) "Device-rendered preview" else null,
                imagePath = originalFile.absolutePath,
                displayRotationDegrees = originalDisplayRotation,
            )
        )
        if (preprocessedFiles.isNotEmpty()) {
            preprocessedFiles.forEachIndexed { index, preprocessedFile ->
                results.add(
                    BenchmarkResult(
                        id = "preprocessed_$index",
                        title = "Pre-processed Input",
                        imagePath = preprocessedFile.absolutePath,
                        preprocessedFrameIndex = index,
                        preprocessedFrameCount = preprocessedFiles.size,
                    )
                )
            }
        }

        val pendingOutputSaves = mutableListOf<Triple<String, File, Bitmap>>()

        for (algo in algorithms) {
            val minFrames = algo.metadata.frameRequirements.minFrames
            if (frames.size < minFrames) {
                Log.w("CameraViewModel", "Skipping ${algo.name}: needs $minFrames frames, have ${frames.size}")
                continue
            }

            // Single-frame algorithms get first frame; multi-frame get all available
            val inputFrames = if (minFrames == 1 && algo.metadata.frameRequirements.maxFrames == 1) {
                listOf(frames.first())
            } else {
                val max = algo.metadata.frameRequirements.maxFrames ?: frames.size
                frames.take(max)
            }
            val inputFrameIndices = if (inputFrames.size == 1) {
                listOf(0)
            } else {
                inputFrames.indices.toList()
            }

            val input = AlgorithmInput(
                frames = inputFrames,
                width = width,
                height = height,
                exposureTimes = exposureTimes.take(inputFrames.size),
                isoValues = isoValues.take(inputFrames.size),
                captureTimeMs = captureTimeMs,
            )

            try {
                // Constructed inside the try so an out-of-range tuning combination surfaces as a
                // failed tile rather than taking down the whole run.
                val overrides = _algorithmParameters.value[algo.name].orEmpty()
                val effective = algo.metadata.parameters.effectiveValues(overrides)
                val configured = algo.withParameters(overrides)
                val output = logTimed("algo.process(${algo.name})") { configured.process(input) }
                require(output.pixels.size == output.width * output.height) {
                    "${algo.name} returned ${output.pixels.size} pixels for ${output.width}x${output.height}"
                }
                Log.d("Perf", "algo.output(${algo.name}): ${output.width}x${output.height}, ${output.pixels.size} pixels")
                logMemory("after algo.process(${algo.name})")
                val id = algo.name.lowercase(Locale.US)

                // File path/artifact are known synchronously; the actual PNG encode is deferred
                // until after the algorithm loop below so it never contends with process() timing.
                val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
                val outFile = File(context.filesDir, "OUT_${algo.name}_${sdf.format(Date())}.png")
                artifacts.add(
                    ExportImageArtifact(
                        id = "image_output_$id",
                        path = outFile.absolutePath,
                        role = "algorithm_output",
                        width = output.width,
                        height = output.height,
                    )
                )
                // Copy pixels into a Bitmap now: on API 26+ bitmap pixel data lives in the native
                // heap, so retained outputs stop counting against the per-app Java heap limit that
                // 12.5MP IntArrays exhausted once several algorithms were enabled.
                val outBitmap = Bitmap.createBitmap(output.width, output.height, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(output.pixels, 0, output.width, 0, 0, output.width, output.height)
                pendingOutputSaves.add(Triple(algo.name, outFile, outBitmap))

                outputRecords.add(AlgorithmOutputRecord(id, outFile.absolutePath, output.totalTime, effective))
                results.add(BenchmarkResult(
                    id = id,
                    title = algo.name,
                    subtitle = describeOverrides(algo, overrides),
                    imagePath = outFile.absolutePath,
                    inputFrameIndices = inputFrameIndices,
                    metrics = BenchmarkMetrics(runtimeMs = output.totalTime)
                ))
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Algorithm ${algo.name} failed", e)
                results.add(BenchmarkResult(
                    id = algo.name.lowercase(Locale.US),
                    title = "${algo.name} (failed)",
                    imagePath = originalFile.absolutePath,
                    displayRotationDegrees = originalDisplayRotation,
                    inputFrameIndices = inputFrameIndices,
                    metrics = BenchmarkMetrics()
                ))
            }
        }

        logMemory("before PNG saves (${preprocessedFiles.size} inputs, ${pendingOutputSaves.size} outputs)")
        if (reusePreprocessedPaths == null) {
            preprocessedFiles.forEachIndexed { index, preprocessedFile ->
                val pixels = frames[index]
                saveJobs += async(Dispatchers.Default) {
                    logTimed("saveArgbPng preprocessed[$index] (${width}x$height)") {
                        saveArgbPng(preprocessedFile, pixels, width, height)
                    }
                }
            }
        }
        pendingOutputSaves.forEach { (algoName, outFile, bitmap) ->
            saveJobs += async(Dispatchers.Default) {
                try {
                    logTimed("PNG save output($algoName) (${bitmap.width}x${bitmap.height})") {
                        FileOutputStream(outFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                } finally {
                    bitmap.recycle()
                }
                Unit
            }
        }
        logTimedSuspend("await PNG save jobs x${saveJobs.size}") { saveJobs.awaitAll() }
        logMemory("after PNG saves")

        lastAlgorithmOutputs = outputRecords
        exportImageArtifacts = artifacts
        lastRunInput = LastRunInput(
            originalFilePath = originalFile.absolutePath,
            originalDisplayRotation = originalDisplayRotation,
            preprocessedPaths = preprocessedFiles.map { it.absolutePath },
            width = width,
            height = height,
            exposureTimes = exposureTimes,
            isoValues = isoValues,
            captureTimeMs = captureTimeMs,
            captureMetadata = captureMetadata,
        )
        _canRerun.value = preprocessedFiles.isNotEmpty()
        _benchmarkResults.value = results
        Log.d("Perf", "benchmark results published (${results.size} tiles)")
        refreshHistograms(results)
    }

    /** Short summary of the tuning values the user moved off their defaults, or `null` if none. */
    private fun describeOverrides(
        algorithm: ImageAlgorithm,
        overrides: Map<String, Double>
    ): String? = algorithm.metadata.parameters
        .mapNotNull { parameter ->
            val value = overrides[parameter.id] ?: return@mapNotNull null
            if (value == parameter.default) null
            else "${parameter.label} %.${parameter.decimals}f".format(Locale.US, value)
        }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")

    /**
     * Re-executes the enabled algorithms with the current tuning values against the preprocessed
     * frames of the last run, so parameters can be iterated on without re-capturing.
     */
    fun rerunBenchmarks(context: Context) {
        val previous = lastRunInput ?: return
        if (_isProcessing.value) return

        // Matches takePhoto/loadInputFromGallery: keeps algo.process() off the main thread so the
        // processing indicator keeps animating instead of freezing until the run completes.
        viewModelScope.launch(Dispatchers.IO) {
            _isProcessing.value = true
            val staleOutputPaths = exportImageArtifacts
                .filter { it.role == "algorithm_output" }
                .map { it.path }
            try {
                val frames = withContext(Dispatchers.Default) {
                    logTimed("decode preprocessed frames x${previous.preprocessedPaths.size}") {
                        previous.preprocessedPaths.map { path -> decodeArgbFrame(path) }
                    }
                }
                runBenchmarks(
                    context = context,
                    originalFile = File(previous.originalFilePath),
                    algorithms = getEnabledAlgorithms(),
                    frames = frames,
                    width = previous.width,
                    height = previous.height,
                    exposureTimes = previous.exposureTimes,
                    isoValues = previous.isoValues,
                    captureTimeMs = previous.captureTimeMs,
                    originalDisplayRotation = previous.originalDisplayRotation,
                    captureMetadata = previous.captureMetadata,
                    reusePreprocessedPaths = previous.preprocessedPaths,
                )
                if (_referenceImage.value != null) {
                    logTimed("recomputeMetricsWithReference") { recomputeMetricsWithReference() }
                }
                // Deleted only once the new tiles point at freshly named files.
                withContext(Dispatchers.IO) { deleteFiles(staleOutputPaths) }
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Re-run failed", e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /**
     * Decodes a losslessly persisted preprocessed PNG back to the exact ARGB_8888 pixels the first
     * run fed the algorithms. Decoded one frame at a time: each is ~48 MiB at full sensor size.
     */
    private fun decodeArgbFrame(path: String): IntArray {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(path, options)
            ?: throw IOException("Missing preprocessed frame: $path")
        return try {
            IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun backToCamera() {
        // The just-cleared session's persisted PNG/JPEG/DNG files are otherwise never deleted,
        // which is what let filesDir grow unbounded across repeated test/benchmark runs.
        val staleFilePaths = (_benchmarkResults.value.map { it.imagePath } +
            exportImageArtifacts.map { it.path } +
            listOfNotNull(_referenceImage.value?.displayPath))
            .distinct()

        _currentScreen.value = AppScreen.CAMERA
        histogramGeneration++
        histogramJob?.cancel()
        histogramJob = null
        _histograms.value = emptyMap()
        _benchmarkResults.value = emptyList()
        lastAlgorithmOutputs = emptyList()
        exportImageArtifacts = emptyList()
        lastRunInput = null
        _canRerun.value = false
        captureMetadata = CaptureMetadata(
            sourceFormat = "unknown",
            width = 0,
            height = 0,
            exposureTimesMs = emptyList(),
            isoValues = emptyList(),
            captureTimeMs = 0L,
        )
        _referenceImage.value?.bitmap?.recycle()
        _referenceImage.value = null

        if (staleFilePaths.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                deleteFiles(staleFilePaths)
            }
        }
    }

    /** Deletes persisted files by absolute path, ignoring ones already gone. */
    private fun deleteFiles(paths: List<String>) {
        paths.forEach { path ->
            try {
                val file = File(path)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.w("CameraViewModel", "Failed to delete stale file: $path", e)
            }
        }
    }

    /** Bypasses the camera and runs all registered algorithms on a single picked image. */
    fun loadInputFromGallery(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isProcessing.value = true
            try {
                val persistedImage = logTimed("persistPickedImage") { persistPickedImage(context, uri, "IMG") } ?: return@launch
                val bitmap = logTimed("decodeFile picked image") { BitmapFactory.decodeFile(persistedImage.file.absolutePath) }
                    ?: run {
                        persistedImage.file.delete()
                        return@launch
                    }
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                try {
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                } finally {
                    bitmap.recycle()
                }

                logTimedSuspend("runBenchmarks") {
                    runBenchmarks(
                        context, persistedImage.file, getEnabledAlgorithms(), listOf(pixels), width, height,
                        exposureTimes = listOf(0L), isoValues = listOf(100), captureTimeMs = 0L,
                        originalDisplayRotation = 0,
                        captureMetadata = CaptureMetadata(
                            sourceFormat = persistedImage.sourceFormat,
                            width = width,
                            height = height,
                            exposureTimesMs = listOf(0.0),
                            isoValues = listOf(100),
                            captureTimeMs = 0L,
                        )
                    )
                }
                logTimed("recomputeMetricsWithReference") { recomputeMetricsWithReference() }

                _currentScreen.value = AppScreen.RESULTS
                closeCamera()
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error loading input image", e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /** Loads a user-picked ground-truth image and rescoring existing results against it. */
    fun loadReferenceImage(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val persistedImage = persistPickedImage(context, uri, "REF") ?: return@launch
                val bitmap = BitmapFactory.decodeFile(persistedImage.file.absolutePath)
                    ?: run {
                        persistedImage.file.delete()
                        return@launch
                    }

                _referenceImage.value?.bitmap?.recycle()
                _referenceImage.value = ReferenceImage(bitmap, persistedImage.file.absolutePath)
                exportImageArtifacts = exportImageArtifacts.filterNot { it.role == "reference_image" } +
                    ExportImageArtifact(
                        id = "image_reference",
                        path = persistedImage.file.absolutePath,
                        role = "reference_image",
                        width = bitmap.width,
                        height = bitmap.height,
                    )
                recomputeMetricsWithReference()
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error loading reference image", e)
            }
        }
    }

    fun clearReferenceImage() {
        _referenceImage.value?.bitmap?.recycle()
        _referenceImage.value = null
        exportImageArtifacts = exportImageArtifacts.filterNot { it.role == "reference_image" }
        recomputeMetricsWithReference()
    }

    private fun persistPickedImage(
        context: Context,
        uri: Uri,
        prefix: String,
    ): PersistedPickedImage? {
        val mimeType = context.contentResolver.getType(uri)?.lowercase(Locale.US)
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "img"
        }
        val sourceFormat = when (mimeType) {
            "image/png" -> "PNG"
            "image/jpeg", "image/jpg" -> "JPEG"
            else -> mimeType?.substringAfterLast('/')?.uppercase(Locale.US) ?: "UNKNOWN"
        }
        val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US).format(Date())
        val file = File(context.filesDir, "${prefix}_$timestamp.$extension")

        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                FileOutputStream(file).use { destination -> source.copyTo(destination) }
            }
            PersistedPickedImage(file, sourceFormat)
        } catch (e: Exception) {
            if (file.exists()) file.delete()
            throw e
        }
    }

    /** Rescoring all stored algorithm outputs against the current reference image, if any. */
    private fun recomputeMetricsWithReference() {
        val reference = _referenceImage.value
        val updated = _benchmarkResults.value
            .filterNot { it.id == "reference" }
            .map { result ->
                val record = lastAlgorithmOutputs.find { it.id == result.id }
                if (record != null) {
                    result.copy(metrics = metricsFor(record.runtimeMs, record.imagePath))
                } else {
                    result
                }
            }
            .toMutableList()

        if (reference != null) {
            val preprocessedIndex = updated.indexOfLast { it.id.startsWith("preprocessed_") }
            val insertIndex = if (preprocessedIndex >= 0) preprocessedIndex + 1 else 1
            updated.add(
                insertIndex.coerceAtMost(updated.size),
                BenchmarkResult(id = "reference", title = "Reference", imagePath = reference.displayPath)
            )
        }

        _benchmarkResults.value = updated
        refreshHistograms(updated)
    }

    private fun refreshHistograms(results: List<BenchmarkResult>) {
        val generation = ++histogramGeneration
        histogramJob?.cancel()

        val paths = results.map { it.imagePath }.distinct()
        _histograms.value = _histograms.value.filterKeys { it in paths }

        histogramJob = viewModelScope.launch(Dispatchers.IO) {
            val calculated = logTimed("refreshHistograms decode+calc x${paths.size}") {
                paths.mapNotNull { path ->
                    val bitmap = BitmapFactory.decodeFile(path) ?: return@mapNotNull null
                    val histogram = try {
                        calculateRgbHistogram(bitmap)
                    } catch (_: Exception) {
                        null
                    } finally {
                        bitmap.recycle()
                    }
                    histogram?.let { path to it }
                }.toMap()
            }

            if (isActive && generation == histogramGeneration) {
                _histograms.value = calculated
            }
        }
    }

    // Candidate pixels are reloaded from the losslessly persisted PNG rather than retained in memory.
    private fun metricsFor(runtimeMs: Long, imagePath: String): BenchmarkMetrics = logTimed("metricsFor ($imagePath)") {
        val reference = _referenceImage.value ?: return@logTimed BenchmarkMetrics(runtimeMs = runtimeMs)
        val candidate = logTimed("  decode candidate") { BitmapFactory.decodeFile(imagePath) }
            ?: return@logTimed BenchmarkMetrics(runtimeMs = runtimeMs)
        val width = candidate.width
        val height = candidate.height
        val pixels = IntArray(width * height)
        candidate.getPixels(pixels, 0, width, 0, 0, width, height)
        candidate.recycle()

        val referenceBitmap = logTimed("  decode reference") { BitmapFactory.decodeFile(reference.displayPath) }
            ?: return@logTimed BenchmarkMetrics(runtimeMs = runtimeMs)
        val aligned = logTimed("  centerCropAndScale") { centerCropAndScale(referenceBitmap, width, height) }
        referenceBitmap.recycle()
        val refPixels = IntArray(width * height)
        aligned.getPixels(refPixels, 0, width, 0, 0, width, height)
        aligned.recycle()
        val quality = logTimed("  calculateQualityMetrics") { calculateQualityMetrics(referencePixels = refPixels, candidatePixels = pixels) }
        BenchmarkMetrics(runtimeMs = runtimeMs, psnr = quality.psnr, ssim = quality.ssim)
    }

    fun exportResults(context: Context, exportAsZip: Boolean = false) {
        val results = _benchmarkResults.value
        if (results.isEmpty()) return
        val artifacts = exportImageArtifacts
            .distinctBy { it.path }
            .filter { File(it.path).exists() }

        viewModelScope.launch(Dispatchers.IO) {
            val manifestFile = File(context.cacheDir, "benchmark_results.json")
            manifestFile.writeText(buildExportManifest(artifacts, results))

            val authority = "${context.packageName}.fileprovider"
            val shareUri: android.net.Uri
            if (exportAsZip) {
                val zipFile = File(context.cacheDir, "benchmark_results.zip")
                ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                    zip.putNextEntry(ZipEntry(manifestFile.name))
                    manifestFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()

                    artifacts.forEach { artifact ->
                        val imageFile = File(artifact.path)
                        zip.putNextEntry(ZipEntry(imageFile.name))
                        FileInputStream(imageFile).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                shareUri = FileProvider.getUriForFile(context, authority, zipFile)
            } else {
                val uris = ArrayList<android.net.Uri>()
                uris.add(FileProvider.getUriForFile(context, authority, manifestFile))

                artifacts.forEach { artifact ->
                    val imageFile = File(artifact.path)
                    uris.add(FileProvider.getUriForFile(context, authority, imageFile))
                }

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Export Benchmark Results"))
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export Benchmark Results"))
            }
        }
    }

    private fun buildExportManifest(
        artifacts: List<ExportImageArtifact>,
        results: List<BenchmarkResult>,
    ): String {
        val manifest = JSONObject()
            .put(
                "preprocessing",
                JSONObject()
                    .put("pixel_format", "ARGB_8888")
                    .put("channel_order", "ARGB")
            )
            .put(
                "capture",
                JSONObject()
                    .put("source_format", captureMetadata.sourceFormat)
                    .put("width", captureMetadata.width)
                    .put("height", captureMetadata.height)
                    .put("exposure_times_ms", JSONArray(captureMetadata.exposureTimesMs))
                    .put("iso_values", JSONArray(captureMetadata.isoValues))
                    .put("capture_time_ms", captureMetadata.captureTimeMs)
            )

        val capture = manifest.getJSONObject("capture")
        captureMetadata.rawWhiteLevel?.let { capture.put("raw_white_level", it) }
        captureMetadata.rawBlackLevel?.let { capture.put("raw_black_level", it) }
        captureMetadata.cfaPattern?.let { capture.put("raw_cfa_pattern", it) }
        captureMetadata.whiteBalanceGains?.let { capture.put("white_balance_gains", JSONArray(it)) }

        val imageByPath = artifacts.associateBy { it.path }
        val imageEntries = JSONArray()
        artifacts.forEach { artifact ->
            val entry = JSONObject()
                .put("id", artifact.id)
                .put("role", artifact.role)
                .put("file", File(artifact.path).name)
                .put("width", artifact.width)
                .put("height", artifact.height)
            artifact.frameIndex?.let { entry.put("frame_index", it) }
            imageEntries.put(entry)
        }
        manifest.put("images", imageEntries)

        val resultEntries = JSONArray()
        results.forEach { result ->
            val image = imageByPath[result.imagePath] ?: return@forEach
            val kind = when {
                result.id == "original" -> "original_capture"
                result.id.startsWith("preprocessed_") -> "preprocessed_input"
                result.id == "reference" -> "reference_image"
                else -> "algorithm"
            }
            val metrics = result.metrics
            val entry = JSONObject()
                .put("id", result.id)
                .put("kind", kind)
                .put("title", result.title)
                .put("image_id", image.id)
            if (kind == "algorithm") {
                entry.put("input_frame_indices", JSONArray(result.inputFrameIndices))
                entry.put(
                    "metrics",
                    JSONObject()
                        .put("runtime_ms", metrics.runtimeMs ?: JSONObject.NULL)
                        .put("psnr_db", metrics.psnr ?: JSONObject.NULL)
                        .put("ssim", metrics.ssim ?: JSONObject.NULL)
                )
                // Effective values, not just overrides, so the run is reproducible from the export
                // alone even if the built-in defaults change later.
                val parameters = lastAlgorithmOutputs.find { it.id == result.id }?.parameters
                if (!parameters.isNullOrEmpty()) {
                    val parameterEntry = JSONObject()
                    parameters.forEach { (id, value) -> parameterEntry.put(id, value) }
                    entry.put("parameters", parameterEntry)
                }
            } else if (kind == "preprocessed_input") {
                result.preprocessedFrameIndex?.let { entry.put("frame_index", it) }
            }
            resultEntries.put(entry)
        }
        manifest.put("results", resultEntries)
        return manifest.toString(2)
    }

    fun closeCamera() {
        session?.close()
        session = null
        imageReader?.close()
        imageReader = null
        camera?.close()
        camera = null
        _isCameraReady.value = false
    }

    private fun saveArgbPng(file: File, pixels: IntArray, width: Int, height: Int) {
        require(pixels.size == width * height) { "Pixel buffer size does not match image dimensions" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveResult(context: Context, result: CombinedResult, chars: CameraCharacteristics): File {
        val extension = if (result.image.format == ImageFormat.RAW_SENSOR) "dng" else "jpg"
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
        val file = File(context.filesDir, "IMG_${sdf.format(Date())}.$extension")

        try {
            if (result.image.format == ImageFormat.RAW_SENSOR) {
                val dngCreator = android.hardware.camera2.DngCreator(chars, result.metadata)

                val relativeRotation = computeRelativeRotation(chars)
                val mirrored = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                val exifOrientation = com.kbbench.utils.computeExifOrientation(relativeRotation, mirrored)
                dngCreator.setOrientation(exifOrientation)

                FileOutputStream(file).use { dngCreator.writeImage(it, result.image) }
            } else {
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
                FileOutputStream(file).use { it.write(bytes) }
            }
        } catch (e: Exception) {
            if (file.exists()) file.delete()
            throw e
        }
        return file
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(manager: CameraManager, id: String, handler: Handler): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)
            override fun onDisconnected(device: CameraDevice) { device.close() }
            override fun onError(device: CameraDevice, error: Int) {
                cont.resumeWithException(RuntimeException("Camera error $error"))
            }
        }, handler)
    }

    private suspend fun createCaptureSession(device: CameraDevice, targets: List<Surface>, handler: Handler): CameraCaptureSession = suspendCoroutine { cont ->
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
            override fun onConfigureFailed(session: CameraCaptureSession) {
                cont.resumeWithException(RuntimeException("Session config failed"))
            }
        }, handler)
    }

    override fun onCleared() {
        super.onCleared()
        orientationEventListener?.disable()
        session?.close()
        camera?.close()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
        _referenceImage.value?.bitmap?.recycle()
    }

    data class CombinedResult(val image: android.media.Image, val metadata: TotalCaptureResult)
}
