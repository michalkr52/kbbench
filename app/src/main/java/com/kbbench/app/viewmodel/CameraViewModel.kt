package com.kbbench.app.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.OrientationEventListener
import android.view.OrientationEventListener.ORIENTATION_UNKNOWN
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kbbench.algorithm.base.*
import com.kbbench.algorithm.impl.AlgorithmRegistry
import com.kbbench.algorithm.preprocessing.BayerDemosaic
import com.kbbench.algorithm.preprocessing.CfaPattern
import com.kbbench.algorithm.preprocessing.WhiteBalance
import com.kbbench.utils.centerCropAndScale
import com.kbbench.utils.decodeBitmapFromUri
import com.kbbench.utils.getPreviewOutputSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
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
    val extra: Map<String, String> = emptyMap()
) {
    /**
     * Converts metrics into a display-friendly list of key-value pairs.
     */
    fun toDisplayList(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        runtimeMs?.let { list.add("Runtime" to "${it}ms") }
        psnr?.let { list.add("PSNR" to "%.2f dB".format(Locale.US, it)) }
        ssim?.let { list.add("SSIM" to "%.4f".format(Locale.US, it)) }
        extra.forEach { (k, v) -> list.add(k to v) }
        return list
    }
}

data class BenchmarkResult(
    val id: String,
    val title: String,
    val imagePath: String,
    val rotationDegrees: Int = 0,
    val metrics: BenchmarkMetrics = BenchmarkMetrics()
)

/** A user-supplied ground-truth image to score algorithm outputs against. */
data class ReferenceImage(val bitmap: Bitmap, val displayPath: String)

/** Raw output of one algorithm run, kept in memory to rescoring without re-running the algorithm. */
private data class AlgorithmOutputRecord(
    val id: String,
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val runtimeMs: Long,
)

class CameraViewModel : ViewModel() {

    // TODO: Add algorithm selection UI; for now all registered algorithms are used
    private val algorithmRegistry = AlgorithmRegistry()

    private val _currentScreen = MutableStateFlow(AppScreen.CAMERA)
    val currentScreen = _currentScreen.asStateFlow()

    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady = _isCameraReady.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    private val _captureFormat = MutableStateFlow(ImageFormat.JPEG)
    val captureFormat = _captureFormat.asStateFlow()

    private val _referenceComparisonEnabled = MutableStateFlow(true)
    val referenceComparisonEnabled = _referenceComparisonEnabled.asStateFlow()

    private val _referenceImage = MutableStateFlow<ReferenceImage?>(null)
    val referenceImage = _referenceImage.asStateFlow()

    private var lastAlgorithmOutputs: List<AlgorithmOutputRecord> = emptyList()

    private val _benchmarkResults = MutableStateFlow<List<BenchmarkResult>>(emptyList())
    val benchmarkResults = _benchmarkResults.asStateFlow()

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

    private var orientationEventListener: OrientationEventListener? = null
    private var deviceOrientation = 0 // 0, 90, 180, 270

    fun setCaptureFormat(format: Int) {
        _captureFormat.value = format
        if (format == ImageFormat.RAW_SENSOR) {
            _zoomLevel.value = 1f
        }
    }

    fun setReferenceComparisonEnabled(enabled: Boolean) {
        _referenceComparisonEnabled.value = enabled
    }

    fun initialize(context: Context) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()

        cameraId?.let {
            characteristics = manager.getCameraCharacteristics(it)
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
                val algorithms = algorithmRegistry.getAll()
                val maxFramesNeeded = algorithms.maxOfOrNull { it.metadata.frameRequirements.minFrames } ?: 1

                val request = sess.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    if (!isRaw) {
                        applyZoom(this, _zoomLevel.value, chars)
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

                // Save first frame as the original reference
                val firstResult = capturedFrames.first()
                val file = saveResult(context, firstResult, chars)

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

                // Convert captured frames to ARGB pixel arrays for algorithms
                val framePixels = capturedFrames.map { frame -> decodeToArgb(frame, whiteLevel, blackLevel, cfaPattern) }
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

                // Rotation: original JPEG has EXIF (Coil handles it), but algorithm
                // output PNGs and RAW have no EXIF so they need explicit rotation
                val rotation = computeRelativeRotation(chars)
                val originalRotation = if (isRaw) rotation else 0
                val outputRotation = rotation

                // Run algorithms and build results
                runBenchmarks(context, file, algorithms, pixelArrays, width, height,
                    exposureTimes, isoValues, captureTimeMs, originalRotation, outputRotation)
                recomputeMetricsWithReference()

                _currentScreen.value = AppScreen.RESULTS
                closeCamera()
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error taking photo", e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun decodeToArgb(
        frame: CombinedResult,
        whiteLevel: Int = 1023,
        blackLevel: Int = 64,
        cfaPattern: Int = CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
    ): Triple<IntArray, Int, Int> {
        val image = frame.image
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
            val raw = BayerDemosaic.normalizeRaw16(
                rawBuffer = plane.buffer,
                width = w,
                height = h,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                whiteLevel = whiteLevel,
                blackLevel = blackLevel
            )
            val pixels = BayerDemosaic.demosaic(raw, w, h, CfaPattern.fromId(cfaPattern))

            // Apply white balance from capture AWB gains
            val gains = frame.metadata.get(CaptureResult.COLOR_CORRECTION_GAINS)
            if (gains != null) {
                WhiteBalance.apply(pixels, gains.red, gains.greenEven, gains.blue)
            }

            Triple(pixels, w, h)
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

    private fun runBenchmarks(
        context: Context,
        originalFile: File,
        algorithms: List<ImageAlgorithm>,
        frames: List<IntArray>,
        width: Int,
        height: Int,
        exposureTimes: List<Long>,
        isoValues: List<Int>,
        captureTimeMs: Long,
        originalRotation: Int,
        outputRotation: Int
    ) {
        Log.d("CameraViewModel", "Running ${algorithms.size} algorithms on ${frames.size} frame(s)")

        val results = mutableListOf<BenchmarkResult>()
        val outputRecords = mutableListOf<AlgorithmOutputRecord>()

        // Original image as baseline
        results.add(BenchmarkResult(
            id = "original",
            title = "Original",
            imagePath = originalFile.absolutePath,
            rotationDegrees = originalRotation
        ))

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

            val input = AlgorithmInput(
                frames = inputFrames,
                width = width,
                height = height,
                exposureTimes = exposureTimes.take(inputFrames.size),
                isoValues = isoValues.take(inputFrames.size),
                captureTimeMs = captureTimeMs,
            )

            try {
                val output = algo.process(input)
                val id = algo.name.lowercase(Locale.US)

                // Save output pixels as PNG (physically rotated for correct display)
                val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
                val outFile = File(context.filesDir, "OUT_${algo.name}_${sdf.format(Date())}.png")
                var bitmap = Bitmap.createBitmap(output.width, output.height, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(output.pixels, 0, output.width, 0, 0, output.width, output.height)
                if (outputRotation != 0) {
                    val matrix = Matrix().apply { postRotate(outputRotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    bitmap.recycle()
                    bitmap = rotated
                }
                FileOutputStream(outFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()

                outputRecords.add(AlgorithmOutputRecord(id, output.pixels, output.width, output.height, output.totalTime))
                results.add(BenchmarkResult(
                    id = id,
                    title = algo.name,
                    imagePath = outFile.absolutePath,
                    rotationDegrees = 0,
                    metrics = BenchmarkMetrics(runtimeMs = output.totalTime)
                ))
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Algorithm ${algo.name} failed", e)
                results.add(BenchmarkResult(
                    id = algo.name.lowercase(Locale.US),
                    title = "${algo.name} (failed)",
                    imagePath = originalFile.absolutePath,
                    rotationDegrees = originalRotation,
                    metrics = BenchmarkMetrics(extra = mapOf("Error" to (e.message ?: "Unknown")))
                ))
            }
        }

        lastAlgorithmOutputs = outputRecords
        _benchmarkResults.value = results
    }

    fun backToCamera() {
        _currentScreen.value = AppScreen.CAMERA
        _benchmarkResults.value = emptyList()
        lastAlgorithmOutputs = emptyList()
        _referenceImage.value?.bitmap?.recycle()
        _referenceImage.value = null
    }

    /** Bypasses the camera and runs all registered algorithms on a single picked image. */
    fun loadInputFromGallery(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isProcessing.value = true
            try {
                val bitmap = decodeBitmapFromUri(context, uri) ?: return@launch
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
                val file = File(context.filesDir, "IMG_${sdf.format(Date())}.jpg")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                bitmap.recycle()

                runBenchmarks(
                    context, file, algorithmRegistry.getAll(), listOf(pixels), width, height,
                    exposureTimes = listOf(0L), isoValues = listOf(100), captureTimeMs = 0L,
                    originalRotation = 0, outputRotation = 0
                )
                recomputeMetricsWithReference()

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
                val bitmap = decodeBitmapFromUri(context, uri) ?: return@launch
                val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
                val file = File(context.filesDir, "REF_${sdf.format(Date())}.jpg")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }

                _referenceImage.value?.bitmap?.recycle()
                _referenceImage.value = ReferenceImage(bitmap, file.absolutePath)
                recomputeMetricsWithReference()
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error loading reference image", e)
            }
        }
    }

    fun clearReferenceImage() {
        _referenceImage.value?.bitmap?.recycle()
        _referenceImage.value = null
        recomputeMetricsWithReference()
    }

    /** Rescoring all stored algorithm outputs against the current reference image, if any. */
    private fun recomputeMetricsWithReference() {
        val reference = _referenceImage.value
        val updated = _benchmarkResults.value
            .filterNot { it.id == "reference" }
            .map { result ->
                val record = lastAlgorithmOutputs.find { it.id == result.id }
                if (record != null) {
                    result.copy(metrics = metricsFor(record.runtimeMs, record.pixels, record.width, record.height))
                } else {
                    result
                }
            }
            .toMutableList()

        if (reference != null) {
            val insertIndex = if (updated.isNotEmpty()) 1 else 0
            updated.add(
                insertIndex.coerceAtMost(updated.size),
                BenchmarkResult(id = "reference", title = "Reference", imagePath = reference.displayPath)
            )
        }

        _benchmarkResults.value = updated
    }

    private fun metricsFor(runtimeMs: Long, pixels: IntArray, width: Int, height: Int): BenchmarkMetrics {
        val reference = _referenceImage.value ?: return BenchmarkMetrics(runtimeMs = runtimeMs)
        val aligned = centerCropAndScale(reference.bitmap, width, height)
        val refPixels = IntArray(width * height)
        aligned.getPixels(refPixels, 0, width, 0, 0, width, height)
        aligned.recycle()
        val quality = calculateQualityMetrics(referencePixels = refPixels, candidatePixels = pixels)
        return BenchmarkMetrics(runtimeMs = runtimeMs, psnr = quality.psnr, ssim = quality.ssim)
    }

    fun exportResults(context: Context) {
        val results = _benchmarkResults.value
        if (results.isEmpty()) return

        // Build CSV report
        val sb = StringBuilder()
        sb.appendLine("ID,Title,Runtime (ms),PSNR (dB),SSIM,Image Path")
        results.forEach { r ->
            val m = r.metrics
            sb.appendLine("${r.id},${r.title},${m.runtimeMs ?: ""},${m.psnr ?: ""},${m.ssim ?: ""},${r.imagePath}")
        }

        val reportFile = File(context.cacheDir, "benchmark_report.csv")
        reportFile.writeText(sb.toString())

        val authority = "${context.packageName}.fileprovider"
        val uris = ArrayList<android.net.Uri>()

        uris.add(FileProvider.getUriForFile(context, authority, reportFile))

        // Attach unique image files
        val seenPaths = mutableSetOf<String>()
        results.forEach { r ->
            if (seenPaths.add(r.imagePath)) {
                val imageFile = File(r.imagePath)
                if (imageFile.exists()) {
                    uris.add(FileProvider.getUriForFile(context, authority, imageFile))
                }
            }
        }

        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Export Benchmark Results"))
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
