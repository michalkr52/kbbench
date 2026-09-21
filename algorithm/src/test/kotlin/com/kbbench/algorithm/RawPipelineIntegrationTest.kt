package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
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
 * The preprocessor emits the same planar [com.kbbench.algorithm.base.Frame] consumed by algorithms,
 * so a wider sensor source is not quantized to ARGB before execution.
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
        assertEquals(SIZE * SIZE, processed.frame.size)

        val output = GuidedFilterDenoise().process(
            AlgorithmInput(
                frames = listOf(processed.frame),
                exposureTimes = listOf(0L),
                isoValues = listOf(100),
                captureTimeMs = 0L,
            ),
        )

        assertEquals(SIZE, output.width)
        assertEquals(SIZE, output.height)
        assertTrue(output.totalTime >= 0)
    }

    @Test
    fun twelveBitSensorPrecisionSurvivesIntoTheAlgorithmFrame() {
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

        val surviving = processed.frame.red
            .map { it.toInt() and 0xFFFF }
            .distinct()
            .size
        assertTrue(
            surviving > 256,
            "the planar Frame should retain more than 8-bit precision, got $surviving levels",
        )
        assertTrue(
            sensorLevels <= surviving * 4,
            "the fixture should not lose most of its levels: $sensorLevels sensor levels versus " +
                "$surviving surviving",
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
