package com.kbbench.app.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import android.media.ImageReader
import android.net.Uri
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.provider.OpenableColumns
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
import com.kbbench.algorithm.base.DenoiserRunner
import com.kbbench.algorithm.preprocessing.FrameMaterializer
import com.kbbench.app.settings.BenchmarkSettingsStore
import com.kbbench.algorithm.preprocessing.AppliedLensShading
import com.kbbench.algorithm.preprocessing.ArgbTransfer
import com.kbbench.algorithm.preprocessing.PreprocessingConfig
import com.kbbench.algorithm.preprocessing.TransferEncoding
import com.kbbench.algorithm.preprocessing.TransferCurve
import com.kbbench.algorithm.preprocessing.ResolvedRawPreprocessing
import com.kbbench.algorithm.preprocessing.DngParseException
import com.kbbench.app.preprocessing.PreprocessingRecord
import com.kbbench.app.preprocessing.PreprocessingSource
import com.kbbench.app.preprocessing.RawCaptureSnapshot
import com.kbbench.app.preprocessing.RawCropSnapshot
import com.kbbench.app.preprocessing.ShadingMapSnapshot
import com.kbbench.app.preprocessing.summary
import com.kbbench.app.preprocessing.ArgbPng
import com.kbbench.app.preprocessing.FrameArtifact
import com.kbbench.app.preprocessing.DenoiserConfig
import com.kbbench.algorithm.preprocessing.CfaPattern
import com.kbbench.algorithm.preprocessing.DngImage
import com.kbbench.algorithm.preprocessing.DngReader
import com.kbbench.algorithm.preprocessing.RawPreprocessor
import com.kbbench.algorithm.preprocessing.ShadingMap
import com.kbbench.algorithm.preprocessing.WhiteBalanceGains
import com.kbbench.utils.RgbHistogram
import com.kbbench.utils.calculateRgbHistogram
import com.kbbench.utils.centerCropAndScale
import com.kbbench.utils.getPreviewOutputSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
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

/** DngCreator rejects thumbnails larger than this on either axis. */
private const val DNG_THUMBNAIL_MAX_DIMENSION = 256

/** Fixed inset DngCreator writes as DefaultCropOrigin/DefaultCropSize, in sensor pixels. */
private const val DNG_DEFAULT_CROP_MARGIN = 8

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
    val fullscreenSubtitle: String? = null,
    val imagePath: String,
    val displayRotationDegrees: Int = 0,
    val preprocessedFrameIndex: Int? = null,
    val preprocessedFrameCount: Int? = null,
    val capturedFrameCount: Int? = null,
    val inputFrameIndices: List<Int> = emptyList(),
    val metrics: BenchmarkMetrics = BenchmarkMetrics(),
    val canonicalImagePath: String = imagePath,
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

private data class DenoiserRunRecord(
    val id: String,
    val effectiveParameters: Map<String, Double>,
    val runtimeMs: List<Long>,
    val inputArtifactIds: List<String>,
    val outputArtifactIds: List<String>,
    val inputDomain: String,
    val outputDomain: String,
    val inputStorage: String,
    val outputStorage: String,
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
    val preprocessing: PreprocessingRecord = PreprocessingRecord(),
    val denoiser: DenoiserRunRecord? = null,
)

private data class ExportImageArtifact(
    val id: String,
    val path: String,
    val role: String,
    val width: Int,
    val height: Int,
    val frameIndex: Int? = null,
    val sourceDepth: Int? = null,
    val domain: String? = null,
    val storage: String? = null,
    val canonicalImageId: String? = null,
)

private data class FixedBoundaryMetadata(
    val width: Int,
    val height: Int,
    val sourceDepth: Int,
    val domain: FrameDomain,
    val storage: FrameStorage,
)

private data class PersistedPickedImage(
    val file: File,
    val sourceFormat: String,
)

/** An imported DNG rendered by this app's RAW pipeline, with the metadata that drove the render. */
private class ImportedRawFrame(
    val frame: Frame,
    val width: Int,
    val height: Int,
    val whiteLevel: Int,
    val blackLevel: Int,
    val cfaPattern: Int,
    val whiteBalanceGains: List<Float>?,
    val preprocessing: PreprocessingRecord,
)

private data class DecodedFrame(
    val frame: Frame,
    val width: Int,
    val height: Int,
    val rawSettings: ResolvedRawPreprocessing? = null,
)

/** Everything needed to re-execute algorithms on an already prepared capture. */
private data class LastRunInput(
    val originalFilePath: String,
    val originalDisplayRotation: Int,
    val sourceFilePaths: List<String> = listOf(originalFilePath),
    val preprocessedPaths: List<String>,
    val fixedPipelinePaths: List<String> = emptyList(),
    val width: Int,
    val height: Int,
    val exposureTimes: List<Long>,
    val isoValues: List<Int>,
    val captureTimeMs: Long,
    val captureMetadata: CaptureMetadata,
    val denoiserConfig: DenoiserConfig = DenoiserConfig(),
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = BenchmarkSettingsStore(application)

    private val _preprocessingConfig = MutableStateFlow(settingsStore.loadPreprocessingConfig())
    val preprocessingConfig = _preprocessingConfig.asStateFlow()
    private val _denoiserConfig = MutableStateFlow(settingsStore.loadDenoiserConfig())
    val denoiserConfig = _denoiserConfig.asStateFlow()
    private val _processingError = MutableStateFlow<String?>(null)
    val processingError = _processingError.asStateFlow()
    private val _dngFallbackRequested = MutableStateFlow(false)
    val dngFallbackRequested = _dngFallbackRequested.asStateFlow()
    private var dngFallbackAnswer: CompletableDeferred<Boolean>? = null

    fun setPreprocessingConfig(config: PreprocessingConfig) {
        if (_isProcessing.value) return
        _preprocessingConfig.value = config
        settingsStore.savePreprocessingConfig(config)
    }

    fun setDenoiserConfig(config: DenoiserConfig) {
        if (_isProcessing.value) return
        _denoiserConfig.value = config
        settingsStore.saveDenoiserConfig(config)
    }

    fun dismissProcessingError() { _processingError.value = null }

    fun answerDngFallback(accept: Boolean) {
        dngFallbackAnswer?.complete(accept)
    }

    private suspend fun requestDngFallback(): Boolean {
        val answer = CompletableDeferred<Boolean>()
        dngFallbackAnswer = answer
        _dngFallbackRequested.value = true
        return try { answer.await() } finally {
            _dngFallbackRequested.value = false
            dngFallbackAnswer = null
        }
    }

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

    private val _showRuleOfThirds = MutableStateFlow(settingsStore.loadShowRuleOfThirds())
    val showRuleOfThirds = _showRuleOfThirds.asStateFlow()

    private val _referenceImage = MutableStateFlow<ReferenceImage?>(null)
    val referenceImage = _referenceImage.asStateFlow()

    private var lastAlgorithmOutputs: List<AlgorithmOutputRecord> = emptyList()

    private var lastRunInput: LastRunInput? = null

    private val _canRerun = MutableStateFlow(false)
    val canRerun = _canRerun.asStateFlow()
    private val _canReprocessFromSource = MutableStateFlow(false)
    val canReprocessFromSource = _canReprocessFromSource.asStateFlow()

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

    fun setShowRuleOfThirds(show: Boolean) {
        _showRuleOfThirds.value = show
        settingsStore.saveShowRuleOfThirds(show)
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
            val prefixes = listOf("IMG_", "REF_", "PREPROCESSED_", "OUT_", "PNG_", "PREVIEW_")
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
        if (_isProcessing.value) return
        val sess = session ?: return
        val reader = imageReader ?: return
        val id = cameraId ?: return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = characteristics ?: manager.getCameraCharacteristics(id)

        val profile = _preprocessingConfig.value
        val denoiserConfig = _denoiserConfig.value
        val algorithms = getEnabledAlgorithms()
        val parameters = _algorithmParameters.value
        _isProcessing.value = true

        viewModelScope.launch(Dispatchers.IO) {
            var capturedImages = emptyList<CombinedResult>()
            val persistedSourceFiles = mutableListOf<File>()
            var preparedFixedPipelinePaths = emptyList<File>()
            var fixedPipelinePathsAdopted = false
            try {
                val isRaw = _captureFormat.value == ImageFormat.RAW_SENSOR
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
                capturedImages = capturedFrames
                val captureTimeMs = System.currentTimeMillis() - captureStartMs
                Log.d("Perf", "camera capture x${capturedFrames.size}: $captureTimeMs ms")

                // Save first frame as the original reference
                val firstResult = capturedFrames.first()

                // Get RAW white/black levels from characteristics for proper normalization
                val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                // DngCreator writes the per-frame dynamic black level when the device reports one,
                // so reading the static pattern here would normalize against a different floor than
                // the DNG declares and a re-imported capture would not reproduce this frame.
                val blackLevel = firstResult.metadata.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                    ?.takeIf { it.size == 4 }
                    ?.let { (it.sum() / 4f).toInt() }
                    ?: chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let {
                        // Average of 4 Bayer channels
                        ((it.getOffsetForIndex(0, 0) + it.getOffsetForIndex(0, 1) +
                          it.getOffsetForIndex(1, 0) + it.getOffsetForIndex(1, 1)) / 4)
                    } ?: 64
                val cfaPattern = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                    ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB

                // Colour rendering is taken once from the first frame: bracketed frames keep auto WB,
                // so per-frame gains would drift and fuse into colour artifacts.
                val referenceMetadata = capturedFrames.first().metadata
                val wbGains = whiteBalanceGains(referenceMetadata)
                val colorMatrix = if (isRaw) cameraToSrgbMatrix(chars, referenceMetadata) else null
                val shadingMap = if (isRaw) lensShadingMap(referenceMetadata) else null

                // Convert captured frames to display-oriented algorithm frames.
                val rotation = computeRelativeRotation(chars)
                // A RAW buffer spans the pre-correction active array; the DefaultCrop area the DNG
                // declares is smaller, and cropping to it after demosaicing drops the border where
                // interpolation has no neighbours and matches what a spec-compliant renderer shows.
                val rawWidth = if (isRaw) firstResult.image.width else 0
                val rawHeight = if (isRaw) firstResult.image.height else 0
                val rawCrop = if (isRaw) {
                    defaultCropRect(rawWidth, rawHeight)
                        ?.also { Log.d("CameraViewModel", "DefaultCrop ${it.width()}x${it.height()} at ${it.left},${it.top}") }
                } else null
                val resolvedFrames = mutableListOf<ResolvedRawPreprocessing>()
                preparedFixedPipelinePaths = if (denoiserConfig.isEnabled) {
                    capturedFrames.indices.map { index ->
                        File(context.filesDir, "FIXED_PIPELINE_${SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US).format(Date())}_$index.kbframe")
                    }
                } else {
                    emptyList()
                }
                val sourceFiles: List<File>
                val sourceFrames: List<Frame>
                val width: Int
                val height: Int
                if (denoiserConfig.isEnabled) {
                    // Keep only one unclipped RAW frame live. The exact boundary is persisted before
                    // moving to the next capture, then runBenchmarks reads it back one at a time.
                    var processedWidth = 0
                    var processedHeight = 0
                    sourceFrames = emptyList()
                    sourceFiles = capturedFrames.mapIndexed { index, captured ->
                        val decoded = logTimed("decode frame[$index]") {
                            decodeToArgb(
                                captured, whiteLevel, blackLevel, cfaPattern,
                                wbGains, colorMatrix, shadingMap, profile,
                                unclippedBoundary = true,
                            )
                        }
                        decoded.rawSettings?.let { resolvedFrames.add(it) }
                        val (croppedFrame, croppedWidth, croppedHeight) = decoded.let { result ->
                            if (rawCrop != null) cropFrame(result.frame, rawCrop)
                            else Triple(result.frame, result.width, result.height)
                        }
                        val dngThumbnail = if (isRaw) {
                            val displayFrame = FrameMaterializer.materialize(croppedFrame, profile.transferCurve)
                            buildDngThumbnail(displayFrame.toArgb(), croppedWidth, croppedHeight, profile.transferCurve)
                        } else null
                        val sourceFile = try {
                            logTimed("saveResult source[$index]") {
                                saveResult(context, captured, chars, rotation, dngThumbnail, index)
                            }.also { persistedSourceFiles += it }
                        } finally {
                            dngThumbnail?.recycle()
                        }
                        val (rotatedFrame, rotatedWidth, rotatedHeight) =
                            rotateFrame(croppedFrame, croppedWidth, croppedHeight, rotation)
                        if (index == 0) {
                            processedWidth = rotatedWidth
                            processedHeight = rotatedHeight
                        } else {
                            require(rotatedWidth == processedWidth && rotatedHeight == processedHeight) {
                                "Captured frames have different processed dimensions"
                            }
                        }
                        FrameArtifact.save(preparedFixedPipelinePaths[index], rotatedFrame)
                        sourceFile
                    }
                    width = processedWidth
                    height = processedHeight
                } else {
                    var decodedFrames = logTimed("decode frames x${capturedFrames.size}") {
                        capturedFrames.map { frame ->
                            val decoded = decodeToArgb(
                                frame, whiteLevel, blackLevel, cfaPattern,
                                wbGains, colorMatrix, shadingMap, profile,
                                unclippedBoundary = false,
                            )
                            decoded.rawSettings?.let { resolvedFrames.add(it) }
                            val (frame, frameWidth, frameHeight) = decoded
                            if (rawCrop != null) cropFrame(frame, rawCrop)
                            else Triple(frame, frameWidth, frameHeight)
                        }
                    }

                    // Retain every source frame so a later profile reprocess can rebuild the complete
                    // bracket instead of pretending the first frame represents all inputs.
                    sourceFiles = capturedFrames.mapIndexed { index, captured ->
                        val dngThumbnail = if (isRaw) {
                            decodedFrames[index].let { (frame, w, h) ->
                                val displayFrame = FrameMaterializer.materialize(frame, profile.transferCurve)
                                buildDngThumbnail(displayFrame.toArgb(), w, h, profile.transferCurve)
                            }
                        } else null
                        try {
                            logTimed("saveResult source[$index]") {
                                saveResult(context, captured, chars, rotation, dngThumbnail, index)
                            }.also { persistedSourceFiles += it }
                        } finally {
                            dngThumbnail?.recycle()
                        }
                    }
                    var framePixels = logTimed("rotate frames x${decodedFrames.size}") {
                        decodedFrames.map { (frame, frameWidth, frameHeight) ->
                            rotateFrame(frame, frameWidth, frameHeight, rotation)
                        }
                    }
                    decodedFrames = emptyList()
                    width = framePixels.first().second
                    height = framePixels.first().third
                    sourceFrames = framePixels.map { it.first }
                    framePixels = emptyList()
                }
                val file = sourceFiles.first()

                // Fix orientation for JPEG
                if (!isRaw) {
                    try {
                        val mirrored = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = com.kbbench.utils.computeExifOrientation(rotation, mirrored)

                        val exif = ExifInterface(file.absolutePath)
                        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                        exif.saveAttributes()
                    } catch (e: Exception) {
                        Log.e("CameraViewModel", "Error saving metadata", e)
                    }
                }

                // Extract exposure metadata from capture results
                val exposureTimes = capturedFrames.map { frame ->
                    frame.metadata.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                }
                val isoValues = capturedFrames.map { frame ->
                    frame.metadata.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                }

                // Close all captured images
                capturedFrames.forEach { it.image.close() }
                capturedImages = emptyList()

                // Run algorithms and build results
                logTimedSuspend("runBenchmarks") {
                    runBenchmarks(context, file, algorithms, sourceFrames, width, height,
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
                            whiteBalanceGains = wbGains?.toList(),
                            preprocessing = PreprocessingRecord(
                                profile, if (isRaw) PreprocessingSource.CAMERA_RAW else PreprocessingSource.RENDERED_IMAGE,
                                resolvedFrames.toList(), colorMatrix?.toList(),
                                captureSnapshot = if (isRaw) RawCaptureSnapshot(
                                    rawWidth = rawWidth,
                                    rawHeight = rawHeight,
                                    whiteLevel = whiteLevel,
                                    blackLevel = blackLevel,
                                    cfaPattern = cfaPattern,
                                    metadataWhiteBalanceGains = wbGains?.toList(),
                                    colorMatrix = colorMatrix?.toList(),
                                    shadingMap = shadingMap?.let(ShadingMapSnapshot::from),
                                    crop = rawCrop?.let {
                                        RawCropSnapshot(it.left, it.top, it.width(), it.height())
                                    },
                                    orientationDegrees = rotation,
                                ) else null,
                            ),
                        ), parameterSnapshot = parameters,
                        sourceFiles = sourceFiles,
                        denoiserConfig = denoiserConfig,
                        fixedPipelinePathsToProcess = preparedFixedPipelinePaths
                            .takeIf { it.isNotEmpty() }
                            ?.map { it.absolutePath },
                    )
                }
                fixedPipelinePathsAdopted = preparedFixedPipelinePaths.isNotEmpty()
                logTimed("recomputeMetricsWithReference") { recomputeMetricsWithReference() }

                _currentScreen.value = AppScreen.RESULTS
                closeCamera()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _processingError.value = e.message ?: "Capture failed"
                Log.e("CameraViewModel", "Error taking photo", e)
            } finally {
                capturedImages.forEach { it.image.close() }
                if (!fixedPipelinePathsAdopted) {
                    deleteFiles(preparedFixedPipelinePaths.map { it.absolutePath })
                }
                val retainedPaths = lastRunInput?.sourceFilePaths.orEmpty().toSet()
                persistedSourceFiles
                    .filterNot { it.absolutePath in retainedPaths }
                    .forEach { it.delete() }
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

    /**
     * Red/green/blue multipliers for the demosaic, green normalized to 1.
     *
     * Prefers SENSOR_NEUTRAL_COLOR_POINT because that is what `DngCreator` stores as AsShotNeutral;
     * deriving them from COLOR_CORRECTION_GAINS instead would white-balance a capture differently
     * from its own DNG and from any standard RAW renderer.
     */
    private fun whiteBalanceGains(metadata: CaptureResult): FloatArray? {
        val neutral = metadata.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        if (neutral != null && neutral.size == 3) {
            val values = FloatArray(3) { neutral[it].toFloat() }
            if (values.all { it > 0f }) {
                return floatArrayOf(values[1] / values[0], 1f, values[1] / values[2])
            }
        }
        Log.w("CameraViewModel", "No neutral colour point; falling back to COLOR_CORRECTION_GAINS")
        return metadata.get(CaptureResult.COLOR_CORRECTION_GAINS)
            ?.let { floatArrayOf(it.red, it.greenEven, it.blue) }
    }

    private fun lensShadingMap(metadata: CaptureResult): ShadingMap? {        val map = metadata.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
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
        whiteBalanceGains: FloatArray? = null,
        colorMatrix: FloatArray? = null,
        shadingMap: ShadingMap? = null,
        config: PreprocessingConfig = PreprocessingConfig(),
        unclippedBoundary: Boolean = false,
    ): DecodedFrame {
        val image = frame.image
        return if (image.format == ImageFormat.JPEG) {
            val buffer = image.planes[0].buffer
            buffer.rewind()
            val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, srgbDecodeOptions())
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val w = bitmap.width
            val h = bitmap.height
            bitmap.recycle()
            prepareRenderedPixels(pixels, config)
            DecodedFrame(Frame.fromArgb(pixels, w, h, sourceDepth = 8), w, h)
        } else {
            // RAW_SENSOR: normalize + Bayer demosaic via preprocessing module
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val processed = RawPreprocessor.process(
                rawBuffer = plane.buffer,
                width = w,
                height = h,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                whiteLevel = whiteLevel,
                blackLevel = blackLevel,
                pattern = CfaPattern.fromId(cfaPattern),
                whiteBalanceGains = preprocessingGains(whiteBalanceGains),
                colorMatrix = colorMatrix,
                shadingMap = shadingMap,
                config = config,
                unclippedBoundary = unclippedBoundary,
            )

            DecodedFrame(processed.frame, w, h, processed.settings)
        }
    }

    private fun preprocessingGains(gains: FloatArray?): WhiteBalanceGains? = gains?.let {
        WhiteBalanceGains(
            red = it.getOrNull(0) ?: 1f,
            green = it.getOrNull(1) ?: 1f,
            blue = it.getOrNull(2) ?: 1f,
        )
    }

    private fun srgbDecodeOptions() = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        if (Build.VERSION.SDK_INT >= 26) inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }

    private fun prepareRenderedPixels(pixels: IntArray, config: PreprocessingConfig) {
        if (config.transferCurve.encoding == TransferEncoding.SRGB && config.exposureOffsetEv == 0.0) return
        val transfer = ArgbTransfer.fromSrgb(config)
        for (index in pixels.indices) pixels[index] = transfer.apply(pixels[index])
    }

    /**
     * The DefaultCrop area the DNG written for this capture will declare.
     *
     * `DngCreator` does not derive this from the active array: it always insets the pre-correction
     * active array by a fixed margin ("Default margin recommended by Adobe for interpolation"), so
     * the capture path has to use the same rule or its frames will not match its own DNG.
     */
    private fun defaultCropRect(width: Int, height: Int): android.graphics.Rect? {
        val margin = DNG_DEFAULT_CROP_MARGIN
        if (width <= margin * 2 || height <= margin * 2) return null
        return android.graphics.Rect(margin, margin, width - margin, height - margin)
    }

    private fun cropArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        crop: android.graphics.Rect,
    ): Triple<IntArray, Int, Int> {
        if (crop.left == 0 && crop.top == 0 && crop.width() == width && crop.height() == height) {
            return Triple(pixels, width, height)
        }
        val cropped = IntArray(crop.width() * crop.height())
        for (y in 0 until crop.height()) {
            System.arraycopy(pixels, (crop.top + y) * width + crop.left, cropped, y * crop.width(), crop.width())
        }
        return Triple(cropped, crop.width(), crop.height())
    }

    private fun cropFrame(
        frame: Frame,
        crop: android.graphics.Rect,
    ): Triple<Frame, Int, Int> {
        if (crop.left == 0 && crop.top == 0 && crop.width() == frame.width && crop.height() == frame.height) {
            return Triple(frame, frame.width, frame.height)
        }
        val size = crop.width() * crop.height()
        val red = if (frame.storage == FrameStorage.BOUNDED_U16) ShortArray(size) else null
        val green = if (frame.storage == FrameStorage.BOUNDED_U16) ShortArray(size) else null
        val blue = if (frame.storage == FrameStorage.BOUNDED_U16) ShortArray(size) else null
        val redFloat = if (frame.storage == FrameStorage.UNCLIPPED_FLOAT) FloatArray(size) else null
        val greenFloat = if (frame.storage == FrameStorage.UNCLIPPED_FLOAT) FloatArray(size) else null
        val blueFloat = if (frame.storage == FrameStorage.UNCLIPPED_FLOAT) FloatArray(size) else null
        for (y in 0 until crop.height()) {
            val sourceOffset = (crop.top + y) * frame.width + crop.left
            val targetOffset = y * crop.width()
            for (x in 0 until crop.width()) {
                val source = sourceOffset + x
                val target = targetOffset + x
                if (frame.storage == FrameStorage.BOUNDED_U16) {
                    red!![target] = frame.red[source]
                    green!![target] = frame.green[source]
                    blue!![target] = frame.blue[source]
                } else {
                    redFloat!![target] = frame.r(source)
                    greenFloat!![target] = frame.g(source)
                    blueFloat!![target] = frame.b(source)
                }
            }
        }
        val cropped = if (frame.storage == FrameStorage.BOUNDED_U16) {
            Frame(red!!, green!!, blue!!, crop.width(), crop.height(), frame.sourceDepth, frame.domain)
        } else {
            Frame.unclipped(
                redFloat!!, greenFloat!!, blueFloat!!,
                crop.width(), crop.height(), frame.sourceDepth, frame.domain,
            )
        }
        return Triple(cropped, crop.width(), crop.height())
    }

    private fun cropAndRotateFrame(
        frame: Frame,
        crop: android.graphics.Rect,
        rotationDegrees: Int,
    ): Triple<Frame, Int, Int> {
        require(crop.left >= 0 && crop.top >= 0 &&
            crop.right <= frame.width && crop.bottom <= frame.height) {
            "RAW crop ${crop.left},${crop.top},${crop.width()}x${crop.height()} is outside " +
                "${frame.width}x${frame.height}"
        }
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        require(normalizedRotation == 0 || normalizedRotation == 90 ||
            normalizedRotation == 180 || normalizedRotation == 270) {
            "Unsupported rotation: $rotationDegrees"
        }
        val cropWidth = crop.width()
        val cropHeight = crop.height()
        if (normalizedRotation == 0 && crop.left == 0 && crop.top == 0 &&
            cropWidth == frame.width && cropHeight == frame.height) {
            return Triple(frame, frame.width, frame.height)
        }
        val outputWidth = if (normalizedRotation == 90 || normalizedRotation == 270) cropHeight else cropWidth
        val outputHeight = if (normalizedRotation == 90 || normalizedRotation == 270) cropWidth else cropHeight
        val outputSize = outputWidth * outputHeight
        val output = if (frame.storage == FrameStorage.BOUNDED_U16) {
            val red = ShortArray(outputSize)
            val green = ShortArray(outputSize)
            val blue = ShortArray(outputSize)
            for (y in 0 until cropHeight) {
                for (x in 0 until cropWidth) {
                    val source = (crop.top + y) * frame.width + crop.left + x
                    val target = when (normalizedRotation) {
                        0 -> y * outputWidth + x
                        90 -> x * outputWidth + (cropHeight - 1 - y)
                        180 -> (cropHeight - 1 - y) * outputWidth + (cropWidth - 1 - x)
                        270 -> (cropWidth - 1 - x) * outputWidth + y
                        else -> error("Unsupported rotation: $rotationDegrees")
                    }
                    red[target] = frame.red[source]
                    green[target] = frame.green[source]
                    blue[target] = frame.blue[source]
                }
            }
            Frame(red, green, blue, outputWidth, outputHeight, frame.sourceDepth, frame.domain)
        } else {
            val red = FloatArray(outputSize)
            val green = FloatArray(outputSize)
            val blue = FloatArray(outputSize)
            for (y in 0 until cropHeight) {
                for (x in 0 until cropWidth) {
                    val source = (crop.top + y) * frame.width + crop.left + x
                    val target = when (normalizedRotation) {
                        0 -> y * outputWidth + x
                        90 -> x * outputWidth + (cropHeight - 1 - y)
                        180 -> (cropHeight - 1 - y) * outputWidth + (cropWidth - 1 - x)
                        270 -> (cropWidth - 1 - x) * outputWidth + y
                        else -> error("Unsupported rotation: $rotationDegrees")
                    }
                    red[target] = frame.r(source)
                    green[target] = frame.g(source)
                    blue[target] = frame.b(source)
                }
            }
            Frame.unclipped(red, green, blue, outputWidth, outputHeight, frame.sourceDepth, frame.domain)
        }
        return Triple(output, outputWidth, outputHeight)
    }

    /**
     * Renders an imported DNG with this app's RAW pipeline instead of the platform decoder, so a
     * re-imported capture reproduces the algorithm input the original run used.
     */
    private fun decodeImportedDng(
        file: File,
        config: PreprocessingConfig,
        captureSnapshot: RawCaptureSnapshot? = null,
        preprocessingSource: PreprocessingSource = PreprocessingSource.IMPORTED_DNG,
        unclippedBoundary: Boolean = false,
    ): ImportedRawFrame {
        val dng = DngReader.read(file)
        val snapshot = captureSnapshot
        require(snapshot == null || snapshot.rawWidth == dng.width && snapshot.rawHeight == dng.height) {
            "RAW capture snapshot dimensions do not match ${file.name}"
        }
        val gains = snapshot?.metadataWhiteBalanceGains?.toFloatArray() ?: dng.whiteBalanceGains
        val matrix = snapshot?.colorMatrix?.toFloatArray()
            ?: selectForwardMatrix(dng)?.let { multiply3x3(xyzD50ToSrgb, it) }
        val shadingMap = snapshot?.shadingMap?.toShadingMap()
        val crop = captureSnapshot?.crop?.let {
            android.graphics.Rect(it.left, it.top, it.left + it.width, it.top + it.height)
        } ?: android.graphics.Rect(
            dng.defaultCropX,
            dng.defaultCropY,
            dng.defaultCropX + dng.defaultCropWidth,
            dng.defaultCropY + dng.defaultCropHeight,
        )
        // A mosaic cannot carry a baked-in rotation, so the file's orientation tag is the only
        // signal; the capture path applies the same turn before algorithms see the frame.
        val (processingSettings, transformed) = run {
            val processed = RawPreprocessor.process(
                rawBuffer = dng.raw,
                width = snapshot?.rawWidth ?: dng.width,
                height = snapshot?.rawHeight ?: dng.height,
                rowStride = dng.rowStride,
                pixelStride = dng.pixelStride,
                whiteLevel = snapshot?.whiteLevel ?: dng.whiteLevel,
                blackLevel = snapshot?.blackLevel ?: dng.blackLevel,
                pattern = snapshot?.cfaPattern?.let(CfaPattern::fromId) ?: dng.cfaPattern,
                whiteBalanceGains = preprocessingGains(gains),
                colorMatrix = matrix,
                shadingMap = shadingMap,
                gainMaps = if (snapshot == null) dng.gainMaps else emptyList(),
                config = config,
                unclippedBoundary = unclippedBoundary,
            )
            if (processed.settings.lensShading == AppliedLensShading.UNAVAILABLE) {
                Log.w("CameraViewModel", "Imported DNG has no usable GainMap opcodes; lens falloff stays uncorrected")
            }
            processed.settings to cropAndRotateFrame(
                processed.frame,
                crop,
                captureSnapshot?.orientationDegrees ?: dng.orientationDegrees,
            )
        }
        val (oriented, width, height) = transformed
        return ImportedRawFrame(
            frame = oriented,
            width = width,
            height = height,
            whiteLevel = dng.whiteLevel,
            blackLevel = dng.blackLevel,
            cfaPattern = dng.cfaPattern.id,
            whiteBalanceGains = gains?.toList(),
            preprocessing = PreprocessingRecord(
                config = config,
                source = preprocessingSource,
                rawSettings = listOf(processingSettings),
                colorMatrix = matrix?.toList(),
                captureSnapshot = captureSnapshot,
            ),
        )
    }

    private fun decodeRetainedSource(
        file: File,
        sourceFormat: String,
        config: PreprocessingConfig,
        captureSnapshot: RawCaptureSnapshot?,
        unclippedBoundary: Boolean = false,
    ): ImportedRawFrame {
        if (sourceFormat == "RAW_SENSOR") {
            return try {
                decodeImportedDng(
                    file,
                    config,
                    captureSnapshot = captureSnapshot,
                    preprocessingSource = if (captureSnapshot == null) {
                        PreprocessingSource.IMPORTED_DNG
                    } else {
                        PreprocessingSource.CAMERA_RAW
                    },
                    unclippedBoundary = unclippedBoundary,
                )
            } catch (e: DngParseException) {
                if (captureSnapshot != null) throw e
                Log.w("CameraViewModel", "Reprocessing DNG through rendered fallback", e)
                decodeRenderedSource(file, config, PreprocessingSource.PLATFORM_DNG)
            }
        }
        require(sourceFormat == "JPEG" || sourceFormat == "PNG") {
            "Source format cannot be reprocessed: $sourceFormat"
        }
        return decodeRenderedSource(file, config, PreprocessingSource.RENDERED_IMAGE)
    }

    private fun decodeRenderedSource(
        file: File,
        config: PreprocessingConfig,
        source: PreprocessingSource,
    ): ImportedRawFrame {
        val decoded = BitmapFactory.decodeFile(file.absolutePath, srgbDecodeOptions())
            ?: throw IOException("The device cannot decode ${file.name}")
        val bitmap = applyExifOrientation(decoded, file.absolutePath)
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        try {
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally {
            bitmap.recycle()
        }
        prepareRenderedPixels(pixels, config)
        return ImportedRawFrame(
            frame = Frame.fromArgb(pixels, width, height, sourceDepth = 8),
            width = width,
            height = height,
            whiteLevel = 0,
            blackLevel = 0,
            cfaPattern = 0,
            whiteBalanceGains = null,
            preprocessing = PreprocessingRecord(config = config, source = source),
        )
    }

    /** Mirrors [selectForwardMatrix] for characteristics, using the DNG's own calibration tags. */
    private fun selectForwardMatrix(dng: DngImage): FloatArray? {
        val secondIsD65 =
            dng.calibrationIlluminant2 == CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D65
        return if (secondIsD65) {
            dng.forwardMatrix2 ?: dng.forwardMatrix1
        } else {
            dng.forwardMatrix1 ?: dng.forwardMatrix2
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

    private fun rotateFrame(
        frame: Frame,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ): Triple<Frame, Int, Int> {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (normalizedRotation == 0) return Triple(frame, width, height)
        val rotatedWidth = if (normalizedRotation == 90 || normalizedRotation == 270) height else width
        val rotatedHeight = if (normalizedRotation == 90 || normalizedRotation == 270) width else height
        val rotated = if (frame.storage == FrameStorage.BOUNDED_U16) {
            Frame(
                rotatePlane(frame.red, width, height, normalizedRotation),
                rotatePlane(frame.green, width, height, normalizedRotation),
                rotatePlane(frame.blue, width, height, normalizedRotation),
                rotatedWidth,
                rotatedHeight,
                frame.sourceDepth,
                frame.domain,
            )
        } else {
            Frame.unclipped(
                rotateFloatPlane(frame.floatPlane(0), width, height, normalizedRotation),
                rotateFloatPlane(frame.floatPlane(1), width, height, normalizedRotation),
                rotateFloatPlane(frame.floatPlane(2), width, height, normalizedRotation),
                rotatedWidth,
                rotatedHeight,
                frame.sourceDepth,
                frame.domain,
            )
        }
        return Triple(rotated, rotatedWidth, rotatedHeight)
    }

    private fun rotatePlane(
        pixels: ShortArray,
        width: Int,
        height: Int,
        rotation: Int,
    ): ShortArray = ShortArray(pixels.size).also { output ->
        for (y in 0 until height) {
            for (x in 0 until width) {
                val target = when (rotation) {
                    90 -> x * height + (height - 1 - y)
                    180 -> (height - 1 - y) * width + (width - 1 - x)
                    270 -> (width - 1 - x) * height + y
                    else -> error("Unsupported rotation: $rotation")
                }
                output[target] = pixels[y * width + x]
            }
        }
    }

    private fun rotateFloatPlane(
        pixels: FloatArray,
        width: Int,
        height: Int,
        rotation: Int,
    ): FloatArray = FloatArray(pixels.size).also { output ->
        for (y in 0 until height) {
            for (x in 0 until width) {
                val target = when (rotation) {
                    90 -> x * height + (height - 1 - y)
                    180 -> (height - 1 - y) * width + (width - 1 - x)
                    270 -> (width - 1 - x) * height + y
                    else -> error("Unsupported rotation: $rotation")
                }
                output[target] = pixels[y * width + x]
            }
        }
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
        frames: List<Frame>,
        width: Int,
        height: Int,
        exposureTimes: List<Long>,
        isoValues: List<Int>,
        captureTimeMs: Long,
        originalDisplayRotation: Int,
        captureMetadata: CaptureMetadata,
        reusePreprocessedPaths: List<String>? = null,
        parameterSnapshot: Map<String, Map<String, Double>> = _algorithmParameters.value,
        sourceFiles: List<File> = emptyList(),
        denoiserConfig: DenoiserConfig = _denoiserConfig.value,
        fixedBoundaryFrames: MutableList<Frame>? = null,
        reuseFixedPipelinePaths: List<String>? = null,
        fixedPipelinePathsToProcess: List<String>? = null,
    ) = coroutineScope {
        Log.d("CameraViewModel", "Running ${algorithms.size} algorithms on ${frames.size} frame(s)")

        require(fixedBoundaryFrames == null || fixedBoundaryFrames.size == frames.size) {
            "Fixed-boundary and algorithm frame counts differ"
        }
        val denoiser = denoiserConfig.create()
        val denoiserRuntimes = mutableListOf<Long>()
        var denoiserId: String? = null
        var denoiserEffectiveParameters: Map<String, Double> = emptyMap()
        var denoiserInputDomain: FrameDomain? = null
        var denoiserInputStorage: FrameStorage? = null
        val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US).format(Date())
        val fixedPipelineFiles = when {
            fixedPipelinePathsToProcess != null -> fixedPipelinePathsToProcess.map(::File)
            reuseFixedPipelinePaths != null -> reuseFixedPipelinePaths.map(::File)
            fixedBoundaryFrames != null -> frames.indices.map { index ->
                File(context.filesDir, "FIXED_PIPELINE_${timestamp}_$index.kbframe")
            }
            else -> emptyList()
        }
        val fixedBoundaryMetadata = fixedBoundaryFrames?.map { frame ->
            FixedBoundaryMetadata(
                width = frame.width,
                height = frame.height,
                sourceDepth = frame.sourceDepth,
                domain = frame.domain,
                storage = frame.storage,
            )
        }.orEmpty().toMutableList()
        val newFixedPipelinePaths = if ((fixedBoundaryFrames != null || fixedPipelinePathsToProcess != null) &&
            reuseFixedPipelinePaths == null
        ) {
            fixedPipelineFiles.map { it.absolutePath }
        } else {
            emptyList()
        }
        val algorithmFrames = try {
            when {
                fixedPipelinePathsToProcess != null -> {
                    val materializedFrames = mutableListOf<Frame>()
                    for ((index, fixedFile) in fixedPipelineFiles.withIndex()) {
                        val boundaryFrame = decodeFrameArtifact(fixedFile.absolutePath, width, height)
                        if (fixedBoundaryMetadata.size <= index) {
                            fixedBoundaryMetadata += FixedBoundaryMetadata(
                                width = boundaryFrame.width,
                                height = boundaryFrame.height,
                                sourceDepth = boundaryFrame.sourceDepth,
                                domain = boundaryFrame.domain,
                                storage = boundaryFrame.storage,
                            )
                        }
                        val algorithmFrame = if (denoiser != null) {
                            denoiserInputDomain = boundaryFrame.domain
                            denoiserInputStorage = boundaryFrame.storage
                            val execution = DenoiserRunner.run(
                                boundaryFrame,
                                denoiser,
                                denoiserConfig.parameters(),
                            )
                            denoiserId = execution.denoiserId
                            denoiserEffectiveParameters = execution.effectiveParameters
                            denoiserRuntimes += execution.elapsedMs
                            FrameMaterializer.materialize(
                                execution.output,
                                captureMetadata.preprocessing.config.transferCurve,
                            )
                        } else {
                            FrameMaterializer.materialize(
                                boundaryFrame,
                                captureMetadata.preprocessing.config.transferCurve,
                            )
                        }
                        materializedFrames += algorithmFrame
                    }
                    materializedFrames
                }
                fixedBoundaryFrames != null && denoiser != null -> {
                    for (index in fixedBoundaryFrames.indices) {
                        var boundaryFrame = fixedBoundaryFrames[index]
                        if (reuseFixedPipelinePaths == null) {
                            FrameArtifact.save(fixedPipelineFiles[index], boundaryFrame)
                        }
                        denoiserInputDomain = boundaryFrame.domain
                        denoiserInputStorage = boundaryFrame.storage
                        val execution = DenoiserRunner.run(
                            boundaryFrame,
                            denoiser,
                            denoiserConfig.parameters(),
                        )
                        denoiserId = execution.denoiserId
                        denoiserEffectiveParameters = execution.effectiveParameters
                        denoiserRuntimes += execution.elapsedMs
                        fixedBoundaryFrames[index] = execution.output
                        boundaryFrame = execution.output
                        fixedBoundaryFrames[index] = FrameMaterializer.materialize(
                            boundaryFrame,
                            captureMetadata.preprocessing.config.transferCurve,
                        )
                    }
                    fixedBoundaryFrames
                }
                fixedBoundaryFrames != null -> {
                    fixedBoundaryFrames.forEachIndexed { index, boundaryFrame ->
                        fixedBoundaryFrames[index] = FrameMaterializer.materialize(
                            boundaryFrame,
                            captureMetadata.preprocessing.config.transferCurve,
                        )
                    }
                    fixedBoundaryFrames
                }
                else -> frames
            }
        } catch (failure: Throwable) {
            deleteFiles(newFixedPipelinePaths)
            throw failure
        }

        val results = mutableListOf<BenchmarkResult>()
        val outputRecords = mutableListOf<AlgorithmOutputRecord>()
        val artifacts = mutableListOf<ExportImageArtifact>()
        val saveJobs = mutableListOf<Deferred<Unit>>()
        histogramGeneration++
        histogramJob?.cancelAndJoin()
        val curve = captureMetadata.preprocessing.config.transferCurve
        val previewTransfer = ArgbTransfer.toSrgb(curve)
        val persistedSources = sourceFiles.ifEmpty { listOf(originalFile) }
        fun pngFile(canonical: File): File = File(
            canonical.parentFile,
            "PNG_${canonical.nameWithoutExtension}.png",
        )
        logMemory("benchmark start")

        fixedPipelineFiles.forEachIndexed { index, fixedFile ->
            val boundary = fixedBoundaryMetadata?.getOrNull(index)
            artifacts.add(
                ExportImageArtifact(
                    id = "image_fixed_pipeline_$index",
                    path = fixedFile.absolutePath,
                    role = "fixed_pipeline_output",
                    width = boundary?.width ?: width,
                    height = boundary?.height ?: height,
                    frameIndex = index,
                    sourceDepth = boundary?.sourceDepth,
                    domain = boundary?.domain?.name?.lowercase(Locale.ROOT)
                        ?: captureMetadata.denoiser?.inputDomain,
                    storage = boundary?.storage?.name?.lowercase(Locale.ROOT)
                        ?: captureMetadata.denoiser?.inputStorage,
                )
            )
        }

        // A re-run points at the exact frame artifacts the first run already wrote. The PNGs next
        // to them are display renditions only and are never used as algorithm inputs.
        val preprocessedFiles = algorithmFrames.indices.map { index ->
            val preprocessedFile = reusePreprocessedPaths?.getOrNull(index)?.let { File(it) }
                ?: File(context.filesDir, "PREPROCESSED_${timestamp}_$index.kbframe")
            artifacts.add(
                ExportImageArtifact(
                    id = "image_algorithm_input_$index",
                    path = preprocessedFile.absolutePath,
                    role = "algorithm_input",
                    width = width,
                    height = height,
                    frameIndex = index,
                    sourceDepth = algorithmFrames[index].sourceDepth,
                    domain = algorithmFrames[index].domain.name.lowercase(Locale.ROOT),
                    storage = algorithmFrames[index].storage.name.lowercase(Locale.ROOT),
                )
            )
            val displayFile = pngFile(preprocessedFile)
            if (displayFile != preprocessedFile) {
                artifacts.add(ExportImageArtifact(
                    id = "image_algorithm_input_png_$index",
                    path = displayFile.absolutePath,
                    role = "display_png",
                    width = width,
                    height = height,
                    frameIndex = index,
                    canonicalImageId = "image_algorithm_input_$index",
                ))
            }
            preprocessedFile
        }

        val denoiserRecord = if (denoiserId != null) {
            val output = algorithmFrames.first()
            DenoiserRunRecord(
            id = denoiserId!!,
            effectiveParameters = denoiserEffectiveParameters,
            runtimeMs = denoiserRuntimes.toList(),
                inputArtifactIds = fixedPipelineFiles.mapIndexed { index, _ -> "image_fixed_pipeline_$index" },
                outputArtifactIds = preprocessedFiles.mapIndexed { index, _ -> "image_algorithm_input_$index" },
                inputDomain = denoiserInputDomain!!.name.lowercase(Locale.ROOT),
                outputDomain = output.domain.name.lowercase(Locale.ROOT),
                inputStorage = denoiserInputStorage!!.name.lowercase(Locale.ROOT),
                outputStorage = output.storage.name.lowercase(Locale.ROOT),
            )
        } else {
            captureMetadata.denoiser?.takeIf {
                reuseFixedPipelinePaths != null && denoiserConfig.isEnabled
            }
        }
        val effectiveCaptureMetadata = captureMetadata.copy(denoiser = denoiserRecord)

        persistedSources.forEachIndexed { index, sourceFile ->
            artifacts.add(
                ExportImageArtifact(
                    id = if (index == 0) "image_original" else "image_source_$index",
                    path = sourceFile.absolutePath,
                    role = if (index == 0) "original_capture" else "source_capture",
                    width = width,
                    height = height,
                    frameIndex = index,
                )
            )
        }
        if (preprocessedFiles.isNotEmpty()) {
            val fullscreenInputSubtitle = listOf(
                effectiveCaptureMetadata.preprocessing.config.summary(),
                if (effectiveCaptureMetadata.preprocessing.isRaw) "RAW" else "Rendered",
                denoiserConfig.summary(),
            ).joinToString(" / ")
            preprocessedFiles.forEachIndexed { index, preprocessedFile ->
                results.add(
                    BenchmarkResult(
                        id = "preprocessed_$index",
                        title = "Pre-processed Input",
                        fullscreenSubtitle = fullscreenInputSubtitle,
                        imagePath = pngFile(preprocessedFile).absolutePath,
                        canonicalImagePath = preprocessedFile.absolutePath,
                        preprocessedFrameIndex = index,
                        preprocessedFrameCount = preprocessedFiles.size,
                    )
                )
            }
        }

        val pendingOutputSaves = mutableListOf<Triple<String, File, Bitmap>>()
        coroutineContext[Job]?.invokeOnCompletion { failure ->
            pendingOutputSaves.forEach { (_, _, bitmap) ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            if (failure != null) {
                deleteFiles(artifacts.filter { artifact ->
                    artifact.role == "algorithm_output" ||
                        artifact.canonicalImageId?.startsWith("image_output_") == true ||
                            (reusePreprocessedPaths == null && (
                                artifact.role == "algorithm_input" ||
                                artifact.canonicalImageId?.startsWith("image_algorithm_input_") == true
                            )) ||
                        ((fixedBoundaryFrames != null || fixedPipelinePathsToProcess != null) &&
                            reuseFixedPipelinePaths == null &&
                            (artifact.role == "fixed_pipeline_output" ||
                                artifact.canonicalImageId?.startsWith("image_fixed_pipeline_") == true))
                }.map { it.path })
            }
        }

        val sourceFrames = algorithmFrames

        for (algo in algorithms) {
            val minFrames = algo.metadata.frameRequirements.minFrames
            if (algorithmFrames.size < minFrames) {
                Log.w("CameraViewModel", "Skipping ${algo.name}: needs $minFrames frames, have ${algorithmFrames.size}")
                continue
            }

            // Single-frame algorithms get first frame; multi-frame get all available
            val inputFrames = if (minFrames == 1 && algo.metadata.frameRequirements.maxFrames == 1) {
                listOf(sourceFrames.first())
            } else {
                val max = algo.metadata.frameRequirements.maxFrames ?: sourceFrames.size
                sourceFrames.take(max)
            }
            val inputFrameIndices = if (inputFrames.size == 1) {
                listOf(0)
            } else {
                inputFrames.indices.toList()
            }

            val input = AlgorithmInput(
                frames = inputFrames,
                exposureTimes = exposureTimes.take(inputFrames.size),
                isoValues = isoValues.take(inputFrames.size),
                captureTimeMs = captureTimeMs,
            )

            try {
                // Constructed inside the try so an out-of-range tuning combination surfaces as a
                // failed tile rather than taking down the whole run.
                val overrides = parameterSnapshot[algo.name].orEmpty()
                val effective = algo.metadata.parameters.effectiveValues(overrides)
                val configured = algo.withParameters(overrides)
                val output = logTimed("algo.process(${algo.name})") { configured.process(input) }
                require(output.frame.size == output.width * output.height) {
                    "${algo.name} returned ${output.frame.size} pixels for ${output.width}x${output.height}"
                }
                Log.d("Perf", "algo.output(${algo.name}): ${output.width}x${output.height}, ${output.frame.size} pixels")
                logMemory("after algo.process(${algo.name})")
                val id = algo.name.lowercase(Locale.US)

                // Persist the exact algorithm frame before processing the next algorithm. Only the
                // display rendition is deferred, so full-resolution output frames do not all stay
                // live until the end of the benchmark loop.
                val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
                val outFile = File(context.filesDir, "OUT_${algo.name}_${sdf.format(Date())}.kbframe")
                logTimed("save frame artifact output(${algo.name}) (${output.width}x${output.height})") {
                    FrameArtifact.save(outFile, output.frame)
                }
                artifacts.add(
                    ExportImageArtifact(
                        id = "image_output_$id",
                        path = outFile.absolutePath,
                        role = "algorithm_output",
                        width = output.width,
                        height = output.height,
                        sourceDepth = output.frame.sourceDepth,
                        domain = output.frame.domain.name.lowercase(Locale.ROOT),
                        storage = output.frame.storage.name.lowercase(Locale.ROOT),
                    )
                )
                val displayFile = pngFile(outFile)
                if (displayFile != outFile) {
                    artifacts.add(ExportImageArtifact(
                        id = "image_output_png_$id",
                        path = displayFile.absolutePath,
                        role = "display_png",
                        width = output.width,
                        height = output.height,
                        canonicalImageId = "image_output_$id",
                    ))
                }
                // The preview is the only output kept until the save jobs run. On API 26+ bitmap
                // pixels live in the native heap, avoiding Java-heap growth from large results.
                val outBitmap = ArgbPng.bitmap(output.frame.toArgb(), output.width, output.height)
                pendingOutputSaves.add(Triple(algo.name, outFile, outBitmap))

                outputRecords.add(AlgorithmOutputRecord(id, displayFile.absolutePath, output.totalTime, effective))
                results.add(BenchmarkResult(
                    id = id,
                    title = algo.name,
                    subtitle = describeOverrides(algo, overrides),
                    imagePath = displayFile.absolutePath,
                    canonicalImagePath = outFile.absolutePath,
                    capturedFrameCount = algorithmFrames.size,
                    inputFrameIndices = inputFrameIndices,
                    metrics = BenchmarkMetrics(runtimeMs = output.totalTime)
                ))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Algorithm ${algo.name} failed", e)
                results.add(BenchmarkResult(
                    id = algo.name.lowercase(Locale.US),
                    title = "${algo.name} (failed)",
                    imagePath = originalFile.absolutePath,
                    displayRotationDegrees = originalDisplayRotation,
                    capturedFrameCount = algorithmFrames.size,
                    inputFrameIndices = inputFrameIndices,
                    metrics = BenchmarkMetrics()
                ))
            }
        }

        logMemory("before artifact saves (${preprocessedFiles.size} inputs, ${pendingOutputSaves.size} PNG renditions)")
        if (reusePreprocessedPaths == null) {
            preprocessedFiles.forEachIndexed { index, preprocessedFile ->
                saveJobs += async(Dispatchers.Default) {
                    logTimed("save frame artifact preprocessed[$index] (${width}x$height)") {
                        FrameArtifact.save(preprocessedFile, algorithmFrames[index])
                        saveFramePreview(pngFile(preprocessedFile), algorithmFrames[index], previewTransfer)
                    }
                }
            }
        } else {
            preprocessedFiles.forEachIndexed { index, preprocessedFile ->
                val displayFile = pngFile(preprocessedFile)
                if (!displayFile.isFile) {
                    saveJobs += async(Dispatchers.Default) {
                        logTimed("restore frame preview[$index] (${width}x$height)") {
                            saveFramePreview(displayFile, algorithmFrames[index], previewTransfer)
                        }
                    }
                }
            }
        }
        pendingOutputSaves.forEach { (algoName, outFile, bitmap) ->
            saveJobs += async(Dispatchers.Default) {
                try {
                    val displayFile = pngFile(outFile)
                    logTimed("PNG save output rendition($algoName) (${bitmap.width}x${bitmap.height})") {
                        ArgbPng.saveDisplay(displayFile, bitmap, previewTransfer)
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
        this@CameraViewModel.captureMetadata = effectiveCaptureMetadata
        lastRunInput = LastRunInput(
            originalFilePath = persistedSources.first().absolutePath,
            originalDisplayRotation = originalDisplayRotation,
            sourceFilePaths = persistedSources.map { it.absolutePath },
            preprocessedPaths = preprocessedFiles.map { it.absolutePath },
            fixedPipelinePaths = fixedPipelineFiles.map { it.absolutePath },
            width = width,
            height = height,
            exposureTimes = exposureTimes,
            isoValues = isoValues,
            captureTimeMs = captureTimeMs,
            captureMetadata = effectiveCaptureMetadata,
            denoiserConfig = denoiserConfig,
        )
        _canRerun.value = preprocessedFiles.isNotEmpty()
        _canReprocessFromSource.value = effectiveCaptureMetadata.sourceFormat in setOf("RAW_SENSOR", "JPEG", "PNG") &&
            persistedSources.isNotEmpty()
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
        val algorithms = getEnabledAlgorithms()
        val parameters = _algorithmParameters.value
        _isProcessing.value = true

        // Matches takePhoto/loadInputFromGallery: keeps algo.process() off the main thread so the
        // processing indicator keeps animating instead of freezing until the run completes.
        viewModelScope.launch(Dispatchers.IO) {
            _isProcessing.value = true
            val staleOutputPaths = exportImageArtifacts
                .filter { it.role == "algorithm_output" || it.canonicalImageId?.startsWith("image_output_") == true }
                .map { it.path }
            try {
                val frames = withContext(Dispatchers.Default) {
                    logTimed("decode preprocessed frames x${previous.preprocessedPaths.size}") {
                        previous.preprocessedPaths.map { path ->
                            decodeFrameArtifact(path, previous.width, previous.height)
                        }
                    }
                }
                runBenchmarks(
                    context = context,
                    originalFile = File(previous.originalFilePath),
                    algorithms = algorithms,
                    frames = frames,
                    width = previous.width,
                    height = previous.height,
                    exposureTimes = previous.exposureTimes,
                    isoValues = previous.isoValues,
                    captureTimeMs = previous.captureTimeMs,
                    originalDisplayRotation = previous.originalDisplayRotation,
                    captureMetadata = previous.captureMetadata,
                    reusePreprocessedPaths = previous.preprocessedPaths,
                    parameterSnapshot = parameters,
                    sourceFiles = previous.sourceFilePaths.map(::File),
                    denoiserConfig = previous.denoiserConfig,
                    reuseFixedPipelinePaths = previous.fixedPipelinePaths,
                )
                if (_referenceImage.value != null) {
                    logTimed("recomputeMetricsWithReference") { recomputeMetricsWithReference() }
                }
                // Deleted only once the new tiles point at freshly named files.
                withContext(Dispatchers.IO) { deleteFiles(staleOutputPaths) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _processingError.value = e.message ?: "Re-run failed"
                Log.e("CameraViewModel", "Re-run failed", e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /** Reapplies only the optional denoiser branch to the last exact fixed-boundary artifacts. */
    fun rerunDenoiser(context: Context) {
        val previous = lastRunInput ?: return
        if (_isProcessing.value) return
        if (previous.fixedPipelinePaths.isEmpty()) {
            reprocessFromSource(context)
            return
        }
        val algorithms = getEnabledAlgorithms()
        val parameters = _algorithmParameters.value
        val denoiserConfig = _denoiserConfig.value
        val stalePaths = exportImageArtifacts
            .filter {
                it.role == "algorithm_input" ||
                    it.role == "algorithm_output" ||
                    it.role == "display_png"
            }
            .map { it.path }
        _isProcessing.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                runBenchmarks(
                    context = context,
                    originalFile = File(previous.originalFilePath),
                    algorithms = algorithms,
                    frames = emptyList(),
                    width = previous.width,
                    height = previous.height,
                    exposureTimes = previous.exposureTimes,
                    isoValues = previous.isoValues,
                    captureTimeMs = previous.captureTimeMs,
                    originalDisplayRotation = previous.originalDisplayRotation,
                    captureMetadata = previous.captureMetadata,
                    parameterSnapshot = parameters,
                    sourceFiles = previous.sourceFilePaths.map(::File),
                    denoiserConfig = denoiserConfig,
                    reuseFixedPipelinePaths = previous.fixedPipelinePaths,
                    fixedPipelinePathsToProcess = previous.fixedPipelinePaths,
                )
                if (_referenceImage.value != null) {
                    logTimed("recomputeMetricsWithReference") { recomputeMetricsWithReference() }
                }
                withContext(Dispatchers.IO) { deleteFiles(stalePaths) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _processingError.value = e.message ?: "Denoiser re-run failed"
                Log.e("CameraViewModel", "Denoiser re-run failed", e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun reprocessFromSource(context: Context) {
        val previous = lastRunInput ?: return
        if (_isProcessing.value) return
        if (previous.captureMetadata.sourceFormat !in setOf("RAW_SENSOR", "JPEG", "PNG")) {
            _processingError.value = "This source format cannot be reprocessed"
            return
        }
        val snapshot = previous.captureMetadata.preprocessing.captureSnapshot
        val sourceFiles = previous.sourceFilePaths.map(::File)
        if (sourceFiles.any { !it.isFile }) {
            _processingError.value = "One or more retained source files are unavailable"
            return
        }
        val algorithms = getEnabledAlgorithms()
        val parameters = _algorithmParameters.value
        val profile = _preprocessingConfig.value
        val denoiserConfig = _denoiserConfig.value
        val stalePaths = exportImageArtifacts
            .filter {
                it.role == "algorithm_input" ||
                    it.role == "fixed_pipeline_output" ||
                    it.role == "algorithm_output" ||
                    it.role == "display_png"
            }
            .map { it.path }
        _isProcessing.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val preparedFixedPipelinePaths = if (denoiserConfig.isEnabled) {
                val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US).format(Date())
                sourceFiles.indices.map { index ->
                    File(context.filesDir, "FIXED_PIPELINE_${timestamp}_$index.kbframe").absolutePath
                }
            } else {
                emptyList()
            }
            var fixedPipelinePathsAdopted = false
            try {
                require(sourceFiles.isNotEmpty()) { "No retained source files" }
                require(sourceFiles.size == previous.exposureTimes.size &&
                    sourceFiles.size == previous.isoValues.size) {
                    "Retained source and capture metadata counts differ"
                }
                val processedFrames: MutableList<Frame>
                val width: Int
                val height: Int
                val metadata: CaptureMetadata
                if (denoiserConfig.isEnabled) {
                    val rawSettings = mutableListOf<ResolvedRawPreprocessing>()
                    var firstPreprocessing: PreprocessingRecord? = null
                    var firstWhiteLevel = 0
                    var firstBlackLevel = 0
                    var firstCfaPattern = 0
                    var firstWhiteBalanceGains: List<Float>? = null
                    var processedWidth = 0
                    var processedHeight = 0

                    withContext(Dispatchers.Default) {
                        sourceFiles.forEachIndexed { index, source ->
                            val decoded = decodeRetainedSource(
                                source,
                                previous.captureMetadata.sourceFormat,
                                profile,
                                captureSnapshot = snapshot,
                                unclippedBoundary = true,
                            )
                            if (index == 0) {
                                processedWidth = decoded.width
                                processedHeight = decoded.height
                                firstPreprocessing = decoded.preprocessing
                                firstWhiteLevel = decoded.whiteLevel
                                firstBlackLevel = decoded.blackLevel
                                firstCfaPattern = decoded.cfaPattern
                                firstWhiteBalanceGains = decoded.whiteBalanceGains
                            } else {
                                require(decoded.width == processedWidth && decoded.height == processedHeight) {
                                    "Retained RAW sources have different processed dimensions"
                                }
                            }
                            rawSettings += decoded.preprocessing.rawSettings
                            FrameArtifact.save(File(preparedFixedPipelinePaths[index]), decoded.frame)
                        }
                    }

                    width = processedWidth
                    height = processedHeight
                    val first = firstPreprocessing ?: error("No retained source files")
                    val preprocessing = first.copy(config = profile, rawSettings = rawSettings)
                    metadata = previous.captureMetadata.copy(
                        width = width,
                        height = height,
                        rawWhiteLevel = firstWhiteLevel.takeIf { preprocessing.isRaw },
                        rawBlackLevel = firstBlackLevel.takeIf { preprocessing.isRaw },
                        cfaPattern = firstCfaPattern.takeIf { preprocessing.isRaw },
                        whiteBalanceGains = firstWhiteBalanceGains,
                        preprocessing = preprocessing,
                    )
                    processedFrames = mutableListOf()
                } else {
                    val decoded = withContext(Dispatchers.Default) {
                        sourceFiles.map { source ->
                            decodeRetainedSource(
                                source,
                                previous.captureMetadata.sourceFormat,
                                profile,
                                captureSnapshot = snapshot,
                                unclippedBoundary = false,
                            )
                        }
                    }
                    width = decoded.first().width
                    height = decoded.first().height
                    require(decoded.all { it.width == width && it.height == height }) {
                        "Retained RAW sources have different processed dimensions"
                    }
                    val first = decoded.first()
                    val preprocessing = first.preprocessing.copy(
                        config = profile,
                        rawSettings = decoded.flatMap { it.preprocessing.rawSettings },
                    )
                    metadata = previous.captureMetadata.copy(
                        width = width,
                        height = height,
                        rawWhiteLevel = first.whiteLevel.takeIf { preprocessing.isRaw },
                        rawBlackLevel = first.blackLevel.takeIf { preprocessing.isRaw },
                        cfaPattern = first.cfaPattern.takeIf { preprocessing.isRaw },
                        whiteBalanceGains = first.whiteBalanceGains,
                        preprocessing = preprocessing,
                    )
                    processedFrames = decoded.map { it.frame }.toMutableList()
                }
                runBenchmarks(
                    context = context,
                    originalFile = sourceFiles.first(),
                    algorithms = algorithms,
                    frames = processedFrames,
                    width = width,
                    height = height,
                    exposureTimes = previous.exposureTimes,
                    isoValues = previous.isoValues,
                    captureTimeMs = previous.captureTimeMs,
                    originalDisplayRotation = previous.originalDisplayRotation,
                    captureMetadata = metadata,
                    parameterSnapshot = parameters,
                    sourceFiles = sourceFiles,
                    denoiserConfig = denoiserConfig,
                    fixedPipelinePathsToProcess = preparedFixedPipelinePaths
                        .takeIf { it.isNotEmpty() },
                )
                fixedPipelinePathsAdopted = preparedFixedPipelinePaths.isNotEmpty()
                if (_referenceImage.value != null) {
                    logTimed("recomputeMetricsWithReference") { recomputeMetricsWithReference() }
                }
                withContext(Dispatchers.IO) { deleteFiles(stalePaths) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _processingError.value = e.message ?: "Source reprocessing failed"
                Log.e("CameraViewModel", "Source reprocessing failed", e)
            } finally {
                if (!fixedPipelinePathsAdopted) {
                    deleteFiles(preparedFixedPipelinePaths)
                }
                _isProcessing.value = false
            }
        }
    }

    /** Decodes one exact preprocessed frame artifact at a time to limit peak memory use. */
    private fun decodeFrameArtifact(path: String, expectedWidth: Int, expectedHeight: Int): Frame {
        val frame = FrameArtifact.load(File(path))
        require(frame.width == expectedWidth && frame.height == expectedHeight) {
            "Preprocessed frame dimensions ${frame.width}x${frame.height} do not match " +
                "the recorded ${expectedWidth}x$expectedHeight"
        }
        return frame
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
        _canReprocessFromSource.value = false
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

    /**
     * Rotates/flips a decoded bitmap to match the file's EXIF/TIFF orientation tag, recycling the input.
     *
     * Decoders disagree about whether they pre-apply the tag: BitmapFactory ignores it for JPEG/PNG,
     * but the platform RAW/DNG path can bake it in while rendering - the same DNG renders upright in
     * Google Photos and quarter-turned in the system photo picker on this project's test device.
     * Rather than hardcoding a per-format assumption that breaks on the next decoder, a quarter-turn
     * tag is treated as already applied when the decoded pixels are already in the post-rotation
     * aspect. Sensor buffers are landscape, so an upright quarter-turned capture must be portrait.
     */
    private fun applyExifOrientation(bitmap: Bitmap, filePath: String): Bitmap {
        val exifOrientation = try {
            ExifInterface(filePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to read EXIF orientation: $filePath", e)
            ExifInterface.ORIENTATION_NORMAL
        }
        val swapsAxes = exifOrientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            exifOrientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            exifOrientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            exifOrientation == ExifInterface.ORIENTATION_TRANSVERSE
        if (swapsAxes && bitmap.height > bitmap.width) {
            Log.d("CameraViewModel", "Decoder already applied orientation $exifOrientation: $filePath")
            return bitmap
        }
        val matrix = com.kbbench.utils.decodeExifOrientation(exifOrientation)
        if (matrix.isIdentity) return bitmap
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to apply EXIF orientation: $filePath", e)
            bitmap
        }
    }

    /** Degrees a viewer must rotate by to honour the file's orientation tag, for renderers that ignore it. */
    private fun exifRotationDegrees(filePath: String): Int = try {
        when (ExifInterface(filePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    } catch (e: Exception) {
        Log.w("CameraViewModel", "Failed to read EXIF orientation: $filePath", e)
        0
    }

    /** Bypasses the camera and runs the currently enabled algorithms on a single picked image. */
    fun loadInputFromGallery(context: Context, uri: Uri) {
        if (_isProcessing.value) return
        val profile = _preprocessingConfig.value
        val denoiserConfig = _denoiserConfig.value
        val algorithms = getEnabledAlgorithms()
        val parameters = _algorithmParameters.value
        _isProcessing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            var sourceFile: File? = null
            try {
                val persistedImage = logTimed("persistPickedImage") { persistPickedImage(context, uri, "IMG") }
                    ?: throw IOException("Unable to read the selected image")
                sourceFile = persistedImage.file
                val isRaw = persistedImage.sourceFormat == "RAW_SENSOR"
                // A DNG must go through this app's RAW pipeline: letting the platform decode it
                // would substitute its own demosaic, white balance and tone curve, so the frame
                // would not reproduce the algorithm input of the capture it came from.
                val rawFrame = if (isRaw) {
                    try {
                        logTimed("decodeImportedDng") {
                            decodeImportedDng(
                                persistedImage.file,
                                profile,
                                unclippedBoundary = denoiserConfig.isEnabled,
                            )
                        }
                    } catch (e: DngParseException) {
                        Log.w("CameraViewModel", "Native DNG import unavailable", e)
                        if (!requestDngFallback()) {
                            persistedImage.file.delete()
                            return@launch
                        }
                        null
                    }
                } else null

                val frame = rawFrame ?: run {
                    val decoded = logTimed("decodeFile picked image") {
                        BitmapFactory.decodeFile(persistedImage.file.absolutePath, srgbDecodeOptions())
                    }
                        ?: throw IOException("The device cannot decode this image")
                    val bitmap = logTimed("applyExifOrientation picked image") {
                        applyExifOrientation(decoded, persistedImage.file.absolutePath)
                    }
                    val decodedWidth = bitmap.width
                    val decodedHeight = bitmap.height
                    val decodedPixels = IntArray(decodedWidth * decodedHeight)
                    try {
                        bitmap.getPixels(decodedPixels, 0, decodedWidth, 0, 0, decodedWidth, decodedHeight)
                    } finally {
                        bitmap.recycle()
                    }
                    prepareRenderedPixels(decodedPixels, profile)
                    ImportedRawFrame(
                        Frame.fromArgb(decodedPixels, decodedWidth, decodedHeight, sourceDepth = 8),
                        decodedWidth, decodedHeight, 0, 0, 0, null,
                        PreprocessingRecord(profile, if (isRaw) PreprocessingSource.PLATFORM_DNG else PreprocessingSource.RENDERED_IMAGE),
                    )
                }
                val width = frame.width
                val height = frame.height
                val processedFrames = mutableListOf(frame.frame)

                logTimedSuspend("runBenchmarks") {
                    runBenchmarks(
                        context, persistedImage.file, algorithms, processedFrames, width, height,
                        exposureTimes = listOf(0L), isoValues = listOf(100), captureTimeMs = 0L,
                        // The results image loader renders a DNG without honouring its TIFF orientation
                        // tag, so an imported RAW needs the same viewer-side compensation the capture
                        // path already applies. JPEG/PNG stay at 0 because the loader does honour EXIF there.
                        originalDisplayRotation = if (isRaw) {
                            exifRotationDegrees(persistedImage.file.absolutePath)
                        } else 0,
                        captureMetadata = CaptureMetadata(
                            sourceFormat = persistedImage.sourceFormat,
                            width = width,
                            height = height,
                            exposureTimesMs = listOf(0.0),
                            isoValues = listOf(100),
                            captureTimeMs = 0L,
                            rawWhiteLevel = rawFrame?.whiteLevel,
                            rawBlackLevel = rawFrame?.blackLevel,
                            cfaPattern = rawFrame?.cfaPattern,
                            whiteBalanceGains = rawFrame?.whiteBalanceGains,
                            preprocessing = frame.preprocessing,
                        ),
                        parameterSnapshot = parameters,
                        denoiserConfig = denoiserConfig,
                        fixedBoundaryFrames = if (denoiserConfig.isEnabled) processedFrames else null,
                    )
                }
                logTimed("recomputeMetricsWithReference") { recomputeMetricsWithReference() }

                _currentScreen.value = AppScreen.RESULTS
                closeCamera()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _processingError.value = e.message ?: "Image import failed"
                Log.e("CameraViewModel", "Error loading input image", e)
            } finally {
                sourceFile?.takeIf { it.absolutePath != lastRunInput?.originalFilePath }?.delete()
                _isProcessing.value = false
            }
        }
    }

    /** Loads a user-picked ground-truth image and rescoring existing results against it. */
    fun loadReferenceImage(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val persistedImage = persistPickedImage(context, uri, "REF") ?: return@launch
                val bitmap = BitmapFactory.decodeFile(persistedImage.file.absolutePath, srgbDecodeOptions())
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
        // Content providers often report a null or generic mime type for DNG, so also fall back
        // to the display name/path extension; without this a picked DNG fails the "RAW_SENSOR"
        // check below and gets mislabeled "Camera JPEG" in the results screen.
        val isDng = mimeType?.contains("dng") == true ||
            queryDisplayName(context, uri)?.endsWith(".dng", ignoreCase = true) == true
        val extension = when {
            isDng -> "dng"
            mimeType == "image/png" -> "png"
            mimeType == "image/jpeg" || mimeType == "image/jpg" -> "jpg"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "img"
        }
        val sourceFormat = when {
            isDng -> "RAW_SENSOR"
            mimeType == "image/png" -> "PNG"
            mimeType == "image/jpeg" || mimeType == "image/jpg" -> "JPEG"
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

    /** Resolves a content:// URI's display name (used to sniff formats mime type lookup misses). */
    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
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
                    val bitmap = BitmapFactory.decodeFile(path, srgbDecodeOptions()) ?: return@mapNotNull null
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

    // Candidate pixels are reloaded from the persisted PNG rather than retained in memory.
    private fun metricsFor(runtimeMs: Long, imagePath: String): BenchmarkMetrics = logTimed("metricsFor ($imagePath)") {
        val reference = _referenceImage.value ?: return@logTimed BenchmarkMetrics(runtimeMs = runtimeMs)
        val candidate = logTimed("  decode candidate") { BitmapFactory.decodeFile(imagePath, srgbDecodeOptions()) }
            ?: return@logTimed BenchmarkMetrics(runtimeMs = runtimeMs)
        val width = candidate.width
        val height = candidate.height
        val pixels = IntArray(width * height)
        candidate.getPixels(pixels, 0, width, 0, 0, width, height)
        candidate.recycle()

        val referenceBitmap = logTimed("  decode reference") { BitmapFactory.decodeFile(reference.displayPath, srgbDecodeOptions()) }
            ?: return@logTimed BenchmarkMetrics(runtimeMs = runtimeMs)
        val aligned = logTimed("  centerCropAndScale") { centerCropAndScale(referenceBitmap, width, height) }
        referenceBitmap.recycle()
        val refPixels = IntArray(width * height)
        aligned.getPixels(refPixels, 0, width, 0, 0, width, height)
        aligned.recycle()
        val quality = logTimed("  calculateQualityMetrics") { calculateQualityMetrics(referencePixels = refPixels, candidatePixels = pixels) }
        BenchmarkMetrics(runtimeMs = runtimeMs, psnr = quality.psnr, ssim = quality.ssim)
    }

    fun exportResults(
        context: Context,
        exportAsZip: Boolean = false,
        includePngImages: Boolean = true,
    ) {
        val results = _benchmarkResults.value
        if (results.isEmpty()) return
        val artifacts = exportImageArtifacts
            .distinctBy { it.path }
            .filter { includePngImages || it.role != "display_png" }
            .filter { File(it.path).exists() }
        val captureSnapshot = captureMetadata
        val outputSnapshot = lastAlgorithmOutputs

        viewModelScope.launch(Dispatchers.IO) {
            val manifestFile = File(context.cacheDir, "benchmark_results.json")
            manifestFile.writeText(buildExportManifest(artifacts, results, captureSnapshot, outputSnapshot))

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
        captureMetadata: CaptureMetadata,
        outputRecords: List<AlgorithmOutputRecord>,
    ): String {
        val manifest = JSONObject()
            .put(
                "preprocessing",
                captureMetadata.preprocessing.toJson()
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
        captureMetadata.denoiser?.let { denoiser ->
            val parameters = JSONObject()
            denoiser.effectiveParameters.forEach { (id, value) -> parameters.put(id, value) }
            manifest.put(
                "denoiser",
                JSONObject()
                    .put("id", denoiser.id)
                    .put("parameters", parameters)
                    .put("runtime_ms", JSONArray(denoiser.runtimeMs))
                    .put("input_image_ids", JSONArray(denoiser.inputArtifactIds))
                    .put("output_image_ids", JSONArray(denoiser.outputArtifactIds))
                    .put("input_domain", denoiser.inputDomain)
                    .put("output_domain", denoiser.outputDomain)
                    .put("input_storage", denoiser.inputStorage)
                    .put("output_storage", denoiser.outputStorage)
            )
        }

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
            artifact.sourceDepth?.let { entry.put("source_depth", it) }
            artifact.domain?.let { entry.put("domain", it) }
            artifact.storage?.let { entry.put("storage", it) }
            artifact.canonicalImageId?.let { entry.put("canonical_image_id", it) }
            imageEntries.put(entry)
        }
        manifest.put("images", imageEntries)
        manifest.put("quality_comparison", JSONObject()
            .put("domain", "srgb_rendered_8bit")
            .put("reference_alignment", "center_crop_and_scale"))

        val resultEntries = JSONArray()
        artifacts.firstOrNull { it.role == "original_capture" }?.let { original ->
            resultEntries.put(
                JSONObject()
                    .put("id", "original")
                    .put("kind", "original_capture")
                    .put("title", if (captureMetadata.sourceFormat == "RAW_SENSOR") {
                        "RAW Source (DNG)"
                    } else {
                        "Camera JPEG"
                    })
                    .put("image_id", original.id)
            )
        }
        results.forEach { result ->
            val image = imageByPath[result.canonicalImagePath] ?: return@forEach
            val kind = when {
                result.id.startsWith("preprocessed_") -> "algorithm_input"
                result.id == "reference" -> "reference_image"
                else -> "algorithm"
            }
            val metrics = result.metrics
            val entry = JSONObject()
                .put("id", result.id)
                .put("kind", kind)
                .put("title", result.title)
                .put("image_id", image.id)
            if (result.imagePath != result.canonicalImagePath) {
                imageByPath[result.imagePath]?.let { entry.put("display_image_id", it.id) }
            }
            if (kind == "algorithm") {
                entry.put("input_frame_indices", JSONArray(result.inputFrameIndices))
                entry.put("input_image_ids", JSONArray(result.inputFrameIndices.map { "image_algorithm_input_$it" }))
                entry.put(
                    "metrics",
                    JSONObject()
                        .put("runtime_ms", metrics.runtimeMs ?: JSONObject.NULL)
                        .put("psnr_db", metrics.psnr ?: JSONObject.NULL)
                        .put("ssim", metrics.ssim ?: JSONObject.NULL)
                )
                // Effective values, not just overrides, so the run is reproducible from the export
                // alone even if the built-in defaults change later.
                val parameters = outputRecords.find { it.id == result.id }?.parameters
                if (!parameters.isNullOrEmpty()) {
                    val parameterEntry = JSONObject()
                    parameters.forEach { (id, value) -> parameterEntry.put(id, value) }
                    entry.put("parameters", parameterEntry)
                }
            } else if (kind == "algorithm_input") {
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

    private fun saveFramePreview(file: File, frame: Frame, transfer: ArgbTransfer) {
        val bitmap = ArgbPng.bitmap(frame.toArgb(), frame.width, frame.height)
        try {
            ArgbPng.saveDisplay(file, bitmap, transfer)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Subsamples demosaiced pixels into a [DngCreator]-sized preview.
     *
     * Nearest-neighbour on the source array avoids materialising a full-resolution Bitmap, which is
     * ~48 MiB at 12.5 MP. One integer step for both axes keeps the aspect ratio DngCreator expects.
     */
    private fun buildDngThumbnail(pixels: IntArray, width: Int, height: Int, curve: TransferCurve = TransferCurve()): Bitmap? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val step = ((maxOf(width, height) + DNG_THUMBNAIL_MAX_DIMENSION - 1) / DNG_THUMBNAIL_MAX_DIMENSION)
            .coerceAtLeast(1)
        val thumbWidth = (width / step).coerceAtLeast(1)
        val thumbHeight = (height / step).coerceAtLeast(1)
        val thumbPixels = IntArray(thumbWidth * thumbHeight)
        val transfer = ArgbTransfer.toSrgb(curve)
        for (y in 0 until thumbHeight) {
            val sourceRow = (y * step) * width
            for (x in 0 until thumbWidth) {
                thumbPixels[y * thumbWidth + x] = transfer.apply(pixels[sourceRow + x * step])
            }
        }
        return Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888).apply {
            setPixels(thumbPixels, 0, thumbWidth, 0, 0, thumbWidth, thumbHeight)
        }
    }

    private fun saveResult(
        context: Context,
        result: CombinedResult,
        chars: CameraCharacteristics,
        rotationDegrees: Int,
        dngThumbnail: Bitmap? = null,
        frameIndex: Int = 0,
    ): File {
        val extension = if (result.image.format == ImageFormat.RAW_SENSOR) "dng" else "jpg"
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
        val file = File(context.filesDir, "IMG_${sdf.format(Date())}_$frameIndex.$extension")

        try {
            if (result.image.format == ImageFormat.RAW_SENSOR) {
                val mirrored = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                val exifOrientation = com.kbbench.utils.computeExifOrientation(rotationDegrees, mirrored)
                android.hardware.camera2.DngCreator(chars, result.metadata).use { dngCreator ->
                    dngCreator.setOrientation(exifOrientation)
                    // One orientation tag covers the whole file, so the thumbnail stays sensor-native
                    // like the mosaic it previews.
                    dngThumbnail?.let { dngCreator.setThumbnail(it) }
                    FileOutputStream(file).use { dngCreator.writeImage(it, result.image) }
                }
            } else {
                // decodeToArgb already drained this plane, so remaining() would be 0 without a rewind.
                val buffer = result.image.planes[0].buffer
                buffer.rewind()
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
