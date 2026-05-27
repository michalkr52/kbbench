package com.kbbench.app.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.OrientationEventListener
import android.view.OrientationEventListener.ORIENTATION_UNKNOWN
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class CameraViewModel : ViewModel() {

    private val _currentScreen = MutableStateFlow(AppScreen.CAMERA)
    val currentScreen = _currentScreen.asStateFlow()

    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady = _isCameraReady.asStateFlow()

    private val _captureFormat = MutableStateFlow(ImageFormat.JPEG)
    val captureFormat = _captureFormat.asStateFlow()

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
                imageReader = ImageReader.newInstance(size.width, size.height, currentFormat, 3)

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
            try {
                val isRaw = _captureFormat.value == ImageFormat.RAW_SENSOR
                val request = sess.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    if (!isRaw) {
                        applyZoom(this, _zoomLevel.value, chars)
                    }
                    set(CaptureRequest.JPEG_ORIENTATION, computeRelativeRotation(chars))
                }
                val result = capturePhoto(sess, reader, request)
                val file = saveResult(context, result, chars)

                // Fix orientation for JPEG (DNG orientation is set via DngCreator.setOrientation)
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

                result.image.close()

                // Preprocessing placeholder
                preprocess(file)

                // Display rotation: RAW needs manual rotation, JPEG is handled by Coil/EXIF
                val displayRotation = if (isRaw) {
                    computeRelativeRotation(chars)
                } else 0

                // Trigger benchmarks placeholder
                runBenchmarks(file, displayRotation)

                _currentScreen.value = AppScreen.RESULTS
                closeCamera()
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error taking photo", e)
            }
        }
    }

    private suspend fun preprocess(file: File) {
        // Placeholder for basic preprocessing
        Log.d("CameraViewModel", "Preprocessing image: ${file.absolutePath}")
        kotlinx.coroutines.delay(500) // Simulate work
    }

    private suspend fun runBenchmarks(file: File, displayRotation: Int = 0) {
        // Placeholder for multiple algorithms
        Log.d("CameraViewModel", "Running benchmarks for: ${file.absolutePath}")

        val results = mutableListOf<BenchmarkResult>()

        // 1. Add Original (Baseline)
        results.add(BenchmarkResult(
            id = "original",
            title = "Original",
            imagePath = file.absolutePath,
            rotationDegrees = displayRotation
        ))

        // 2. Simulate Algorithm Outputs
        // In a real scenario, these would call your C++/Kotlin algorithms
        // and "tack on" the metrics calculated during execution.
        for (i in 1..3) {
            val simulatedRuntime = (100..500).random().toLong()
            val simulatedPsnr = (2500..4000).random() / 100.0
            val simulatedSsim = (9000..9999).random() / 10000.0

            results.add(BenchmarkResult(
                id = "algo_$i",
                title = "Algorithm $i",
                imagePath = file.absolutePath, // Placeholder: using same image
                rotationDegrees = displayRotation,
                metrics = BenchmarkMetrics(
                    runtimeMs = simulatedRuntime,
                    psnr = simulatedPsnr,
                    ssim = simulatedSsim
                )
            ))
        }

        _benchmarkResults.value = results
    }

    fun backToCamera() {
        _currentScreen.value = AppScreen.CAMERA
        _benchmarkResults.value = emptyList()
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

    private suspend fun capturePhoto(session: CameraCaptureSession, reader: ImageReader, request: CaptureRequest.Builder): CombinedResult = suspendCoroutine { cont ->
        // Flush any images left in the image reader
        while (true) {
            val oldImage = reader.acquireNextImage() ?: break
            oldImage.close()
        }

        val imageQueue = ArrayBlockingQueue<android.media.Image>(3)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage()
            imageQueue.add(image)
        }, imageReaderHandler)

        session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val image = imageQueue.take()
                cont.resume(CombinedResult(image, result))
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                cont.resumeWithException(RuntimeException("Capture failed: ${failure.reason}"))
            }
        }, cameraHandler)
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
    }

    data class CombinedResult(val image: android.media.Image, val metadata: TotalCaptureResult)
}
