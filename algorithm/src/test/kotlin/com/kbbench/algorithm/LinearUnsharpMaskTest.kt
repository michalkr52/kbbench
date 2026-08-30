package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.impl.AdaptiveUnsharpMasking
import com.kbbench.algorithm.impl.LinearUnsharpMasking
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinearUnsharpMaskTest {

    /** The Laplacian of Eq. (2) is zero on a flat image, so Eq. (1) leaves it alone. */
    @Test
    fun constantImageIsUnchanged() {
        val width = 20
        val height = 20
        val src = IntArray(width * height) { argb(255, 90, 140, 30) }

        assertContentEquals(src, sharpen(src, width, height))
    }

    /** `lambda = 0` drops the correction term of Eq. (1) entirely. */
    @Test
    fun zeroGainLeavesImageUnchanged() {
        val width = 37
        val height = 23
        val src = noiseImage(width, height, seed = 17)

        assertContentEquals(src, sharpen(src, width, height, lambda = 0.0))
    }

    /**
     * The algorithm is closed form, so a reference transcribed straight from Eq. (1) and (2) has to
     * reproduce it bit for bit — including the rolling three-row buffer's border replication, which
     * the reference expresses by clamping the row index instead.
     */
    @Test
    fun matchesClosedFormReference() {
        val width = 31
        val height = 19
        val src = noiseImage(width, height, seed = 5)

        for (lambda in doubleArrayOf(0.25, 0.5, 1.5)) {
            assertContentEquals(
                reference(src, width, height, lambda) { c, up, down, left, right ->
                    4f * c - up - down - left - right
                },
                sharpen(src, width, height, lambda),
                "diverged from Eq. (2) at lambda=$lambda",
            )
        }
    }

    /**
     * What licenses using this as the baseline for [AdaptiveUnsharpMasking]: the two directional
     * Laplacians of Eq. (3) and (4) sum to the highpass of Eq. (2), so linear UM is precisely the
     * adaptive filter with both gains frozen at `lambda`.
     *
     * The identity is exact in real arithmetic; the tolerance covers only the reassociation of the
     * `Float` sum, which can move the rounded channel by one level at a half-way point.
     */
    @Test
    fun equalsFixedGainDirectionalUnsharpMasking() {
        val width = 31
        val height = 19
        val src = noiseImage(width, height, seed = 6)

        val directional = reference(src, width, height, lambda = 0.5) { c, up, down, left, right ->
            val zx = 2f * c - left - right
            val zy = 2f * c - up - down
            zx + zy
        }

        assertChannelsWithin(directional, sharpen(src, width, height), tolerance = 1)
    }

    /**
     * The whole reason this baseline exists. Section III of the paper reports that linear UM
     * amplifies the background noise in smooth areas while the adaptive method does not; here the
     * adaptive filter returns the noisy input untouched, because a local variance below `tau1` makes
     * the desired dynamics equal the input's own and the gains never leave zero.
     */
    @Test
    fun amplifiesSmoothNoiseUnlikeTheAdaptiveVariant() {
        val width = 64
        val height = 48
        val flat = withGaussianNoise(
            IntArray(width * height) { argb(255, 120, 120, 120) },
            sigma = 2.0,
            seed = 9,
        )
        val input = inputOf(flat, width, height)

        val linear = LinearUnsharpMasking().process(input).pixels
        val adaptive = AdaptiveUnsharpMasking().process(input).pixels

        val before = computeMetrics(flat).lumaStd
        val after = computeMetrics(linear).lumaStd

        assertTrue(after > before, "linear UM did not amplify the noise: $before -> $after")
        assertContentEquals(flat, adaptive, "adaptive UM was expected to leave smooth noise alone")
    }

    @Test
    fun preservesAlphaAndDimensions() {
        val width = 15
        val height = 9
        val random = Random(8)
        val src = IntArray(width * height) {
            argb(random.nextInt(256), random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }

        val output = LinearUnsharpMasking().process(inputOf(src, width, height))

        assertEquals(width, output.width)
        assertEquals(height, output.height)
        assertEquals(width * height, output.pixels.size)
        for (i in src.indices) {
            assertEquals(src[i] ushr 24, output.pixels[i] ushr 24, "alpha changed at index $i")
        }
    }

    @Test
    fun rejectsDegenerateParameters() {
        val src = IntArray(16) { argb(255, 10, 10, 10) }

        assertFailsWith<IllegalArgumentException> { LinearUnsharpMasking(lambda = -0.1) }
        assertFailsWith<IllegalArgumentException> {
            LinearUnsharpMasking().process(inputOf(src, 4, 4).copy(frames = emptyList()))
        }
        assertFailsWith<IllegalArgumentException> {
            LinearUnsharpMasking().process(inputOf(src, 5, 4))
        }
        assertFailsWith<IllegalArgumentException> {
            LinearUnsharpMasking().process(inputOf(src, 0, 0))
        }
    }

    private fun sharpen(src: IntArray, width: Int, height: Int, lambda: Double = 0.5): IntArray =
        LinearUnsharpMasking(lambda).process(inputOf(src, width, height)).pixels

    private fun inputOf(src: IntArray, width: Int, height: Int) = AlgorithmInput(
        frames = listOf(src),
        width = width,
        height = height,
        exposureTimes = listOf(10_000_000L),
        isoValues = listOf(100),
        captureTimeMs = 0L,
    )

    /** Eq. (1) evaluated over a full luma plane, with [highpass] supplying the choice of Eq. (2). */
    private fun reference(
        src: IntArray,
        width: Int,
        height: Int,
        lambda: Double,
        highpass: (c: Float, up: Float, down: Float, left: Float, right: Float) -> Float,
    ): IntArray {
        val luma = FloatArray(src.size) { i ->
            val p = src[i]
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }

        return IntArray(src.size) { i ->
            val x = i % width
            val y = i / width
            val z = highpass(
                luma[i],
                luma[max(0, y - 1) * width + x],
                luma[min(height - 1, y + 1) * width + x],
                luma[y * width + max(0, x - 1)],
                luma[y * width + min(width - 1, x + 1)],
            )
            val correction = lambda * z
            val pixel = src[i]
            (pixel and (0xFF shl 24)) or
                (channel(pixel, 16, correction) shl 16) or
                (channel(pixel, 8, correction) shl 8) or
                channel(pixel, 0, correction)
        }
    }

    private fun channel(pixel: Int, shift: Int, correction: Double): Int =
        (((pixel shr shift) and 0xFF) + correction + 0.5).toInt().coerceIn(0, 255)
}
