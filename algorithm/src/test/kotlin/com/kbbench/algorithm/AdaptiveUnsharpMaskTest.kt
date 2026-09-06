package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.filter.AdaptiveDirectionalUnsharpMask
import com.kbbench.algorithm.impl.AdaptiveUnsharpMasking
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdaptiveUnsharpMaskTest {

    /**
     * On a flat image every Laplacian and every highpass response is zero, so the error of Eq. (13)
     * is zero, the gains never leave their initial zero, and the correction of Eq. (5) vanishes.
     */
    @Test
    fun constantImageIsUnchanged() {
        val width = 20
        val height = 20
        val src = IntArray(width * height) { argb(255, 90, 140, 30) }

        assertContentEquals(src, sharpen(src, width, height))
    }

    /**
     * `mu = 0` freezes the Gauss-Newton recursion of Eq. (16) at `A = 0`, which reduces Eq. (5) to
     * `y = x`. Anything the filter does to a real image is therefore attributable to the adaptation.
     */
    @Test
    fun zeroStepSizeLeavesImageUnchanged() {
        val width = 37
        val height = 23
        val src = noiseImage(width, height, seed = 17)

        assertContentEquals(src, sharpen(src, width, height, mu = 0.0))
    }

    /**
     * Below `tau1` the desired dynamics are `alpha_b * g_x = g_x`, so the error is `-A^T G`, which at
     * `A = 0` is exactly zero: the gains never move and the noise passes through untouched.
     *
     * The detail case is checked alongside it so the test cannot pass by the filter being inert.
     */
    @Test
    fun smoothNoiseIsPassedThroughButDetailIsEnhanced() {
        val width = 64
        val height = 48
        val flat = withGaussianNoise(
            IntArray(width * height) { argb(255, 120, 120, 120) },
            sigma = 2.0,
            seed = 9,
        )

        assertContentEquals(flat, sharpen(flat, width, height), "smooth noise was not left alone")

        val detailed = stripeImage(width, height, base = 128, amplitude = 11)
        assertTrue(
            !sharpen(detailed, width, height).contentEquals(detailed),
            "detail was left untouched, so the smooth-area result is vacuous",
        )
    }

    /**
     * `alpha_dh > alpha_dl` in Eq. (12) is what makes medium-contrast detail the most emphasized.
     *
     * A period-2 stripe of amplitude `a` that is constant along the rows has a 3x3 variance of
     * `8 * a^2 / 9`, so `a = 11` lands inside `[tau1, tau2)` and `a = 30` above `tau2`. For such a
     * pattern `g_x = 12a` and `g(z_x) = 48a`, so driving `g_y` to `alpha * g_x` needs a gain of
     * `(alpha - 1) / 4` and multiplies the peak-to-peak swing by exactly `alpha`.
     */
    @Test
    fun mediumContrastIsEnhancedMoreThanHighContrast() {
        val width = 128
        val height = 16
        val row = height / 2

        val mediumGain = amplification(stripeImage(width, height, 128, 11), width, height, row)
        val highGain = amplification(stripeImage(width, height, 128, 30), width, height, row)

        assertTrue(
            mediumGain > highGain,
            "medium contrast was not favoured: medium=$mediumGain high=$highGain",
        )
        assertTrue(
            abs(mediumGain - 4.0) < 0.2,
            "medium-contrast swing should converge to alpha_dh = 4, got $mediumGain",
        )
        assertTrue(
            abs(highGain - 3.0) < 0.2,
            "high-contrast swing should converge to alpha_dl = 3, got $highGain",
        )
    }

    /**
     * The two directions are adapted independently. An image constant along the rows has
     * `z_y = 0` and hence `G[1] = 0`, so the vertical gain never leaves zero and the output must
     * still be constant along the rows.
     */
    @Test
    fun verticalGainStaysIdleWhenThereIsNoVerticalDetail() {
        val width = 48
        val height = 12
        val random = Random(3)
        val row = IntArray(width) { random.nextInt(40, 220) }
        val src = IntArray(width * height) { i -> row[i % width].let { argb(255, it, it, it) } }

        val out = sharpen(src, width, height)

        for (y in 1 until height) {
            for (x in 0 until width) {
                assertEquals(out[x], out[y * width + x], "row $y diverged from row 0 at column $x")
            }
        }
    }

    /**
     * Because Eq. (16) recurses in `m` only, each row restarts from `A = 0` and `R = I`; no state
     * crosses a band boundary and the halo is the only thing banding has to get right.
     */
    @Test
    fun bandedOutputMatchesSingleBandOutput() {
        val width = 29
        val height = 41
        val src = noiseImage(width, height, seed = 11)

        val whole = sharpen(src, width, height, bandHeight = height)
        for (band in intArrayOf(1, 2, 3, 7, 16, 40)) {
            assertContentEquals(whole, sharpen(src, width, height, bandHeight = band), "bandHeight=$band diverged")
        }
    }

    @Test
    fun preservesAlphaAndDimensions() {
        val width = 15
        val height = 9
        val random = Random(8)
        val src = IntArray(width * height) {
            argb(random.nextInt(256), random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        val input = AlgorithmInput(
            frames = listOf(src),
            width = width,
            height = height,
            exposureTimes = listOf(10_000_000L),
            isoValues = listOf(100),
            captureTimeMs = 0L,
        )

        val output = AdaptiveUnsharpMasking().process(input)

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

        assertFailsWith<IllegalArgumentException> { sharpen(src, 5, 4) }
        assertFailsWith<IllegalArgumentException> { sharpen(src, 4, 4, tau1 = 300.0) }
        assertFailsWith<IllegalArgumentException> { sharpen(src, 4, 4, mu = -0.1) }
        assertFailsWith<IllegalArgumentException> { sharpen(src, 4, 4, beta = 0.0) }
        assertFailsWith<IllegalArgumentException> { sharpen(src, 4, 4, beta = 1.0) }

        assertFailsWith<IllegalArgumentException> { AdaptiveUnsharpMasking(tau1 = 200.0, tau2 = 200.0) }
        assertFailsWith<IllegalArgumentException> { AdaptiveUnsharpMasking(alphaB = 0.5) }
        assertFailsWith<IllegalArgumentException> { AdaptiveUnsharpMasking(alphaDl = 1.0) }
        assertFailsWith<IllegalArgumentException> { AdaptiveUnsharpMasking(alphaDl = 4.0, alphaDh = 3.0) }
        assertFailsWith<IllegalArgumentException> { AdaptiveUnsharpMasking(mu = -1.0) }
        assertFailsWith<IllegalArgumentException> { AdaptiveUnsharpMasking(beta = 1.5) }
        assertFailsWith<IllegalArgumentException> { AdaptiveUnsharpMasking(maxGain = 0.0) }
    }

    /** Runs the filter with the paper's Table I defaults, overriding only what a test needs. */
    private fun sharpen(
        src: IntArray,
        width: Int,
        height: Int,
        tau1: Double = 60.0,
        tau2: Double = 200.0,
        mu: Double = 0.1,
        beta: Double = 0.5,
        bandHeight: Int = 0,
    ): IntArray = AdaptiveDirectionalUnsharpMask.sharpen(
        src = src,
        width = width,
        height = height,
        tau1 = tau1,
        tau2 = tau2,
        alphaB = 1.0,
        alphaDl = 3.0,
        alphaDh = 4.0,
        mu = mu,
        beta = beta,
        maxGain = 4.0,
        bandHeight = bandHeight,
    )

    /** Ratio of output to input peak-to-peak swing, measured past the adaptation transient. */
    private fun amplification(src: IntArray, width: Int, height: Int, row: Int): Double {
        val out = sharpen(src, width, height)
        val from = width - 32
        return peakToPeak(out, width, row, from) / peakToPeak(src, width, row, from).toDouble()
    }

    private fun peakToPeak(pixels: IntArray, width: Int, row: Int, fromX: Int): Int {
        var lo = 255
        var hi = 0
        for (x in fromX until width - 1) {
            val v = (pixels[row * width + x] shr 16) and 0xFF
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return hi - lo
    }

    /** Gray period-2 vertical stripes, constant along the rows. */
    private fun stripeImage(width: Int, height: Int, base: Int, amplitude: Int): IntArray =
        IntArray(width * height) { i ->
            val v = if ((i % width) % 2 == 0) base + amplitude else base - amplitude
            argb(255, v, v, v)
        }

}
