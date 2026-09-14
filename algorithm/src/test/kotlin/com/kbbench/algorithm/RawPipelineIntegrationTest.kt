package com.kbbench.algorithm

import com.kbbench.algorithm.base.Frame
import com.kbbench.algorithm.impl.GuidedFilterDenoise
import com.kbbench.algorithm.preprocessing.CfaPattern
import com.kbbench.algorithm.preprocessing.RawPreprocessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Walks a synthetic 12-bit sensor frame through the same sequence the app does -- preprocess,
 * wrap as a [Frame], run an algorithm -- without needing a device.
 *
 * This is also where the remaining gap is pinned. The module now stores and computes at 16 bits,
 * but [RawPreprocessor] still hands back packed ARGB, so a 12-bit sensor is flattened to 256
 * levels before any algorithm sees it. The assertions below record that as the current behaviour
 * on purpose: when the RAW pipeline learns to emit a [Frame] directly, this test is what fails and
 * says so.
 */
class RawPipelineIntegrationTest {

    @Test
    fun twelveBitSensorFrameReachesAnAlgorithmThroughThePreprocessor() {
        val processed = RawPreprocessor.process(
            rawBuffer = twelveBitRamp(),
            width = SIZE,
            height = SIZE,
            rowStride = SIZE * 2,
            pixelStride = 2,
            whiteLevel = SENSOR_MAX,
            blackLevel = 0,
            pattern = CfaPattern.RGGB,
        )

        assertEquals(SIZE, processed.width)
        assertEquals(SIZE, processed.height)
        assertEquals(SIZE * SIZE, processed.pixels.size)

        // Exactly what CameraViewModel.runBenchmarks does with a preprocessed frame today.
        val frame = Frame.fromArgb(processed.pixels, processed.width, processed.height, sourceDepth = 8)
        val output = GuidedFilterDenoise().process(inputOf(SIZE, SIZE, frame.toArgb()))

        assertEquals(SIZE, output.width)
        assertEquals(SIZE, output.height)
        assertTrue(output.totalTime >= 0)
    }

    /**
     * The gap, stated as a number. The sensor buffer carries 4096 distinct values; what survives
     * the preprocessor cannot exceed the 256 a packed byte holds.
     */
    @Test
    fun preprocessorStillFlattensTwelveBitSensorDataToEightBits() {
        val sensorLevels = (0 until SIZE * SIZE).map { it * SENSOR_MAX / (SIZE * SIZE - 1) }.distinct().size
        assertTrue(sensorLevels > 1000, "the fixture should be a genuine 12-bit ramp, got $sensorLevels levels")

        val processed = RawPreprocessor.process(
            rawBuffer = twelveBitRamp(),
            width = SIZE,
            height = SIZE,
            rowStride = SIZE * 2,
            pixelStride = 2,
            whiteLevel = SENSOR_MAX,
            blackLevel = 0,
            pattern = CfaPattern.RGGB,
        )

        val surviving = processed.pixels.map { (it shr 8) and 0xFF }.distinct().size
        assertTrue(
            surviving <= 256,
            "packed ARGB cannot hold more than 256 levels per channel, got $surviving",
        )
        assertTrue(
            sensorLevels > surviving * 4,
            "the fixture must be much richer than the pipeline output for this to mean anything: " +
                "$sensorLevels sensor levels versus $surviving surviving",
        )
    }

    /** A monotone ramp across the whole 12-bit range, little-endian as the sensor delivers it. */
    private fun twelveBitRamp(): ByteBuffer {
        val pixels = SIZE * SIZE
        return ByteBuffer.allocate(SIZE * 2 * SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until pixels) {
                putShort(i * 2, (i * SENSOR_MAX / (pixels - 1)).toShort())
            }
        }
    }

    private companion object {
        const val SIZE = 64
        const val SENSOR_MAX = (1 shl 12) - 1
    }
}
