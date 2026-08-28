package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.calculateQualityMetrics
import com.kbbench.algorithm.filter.BoxFilter
import com.kbbench.algorithm.filter.GuidedFilter
import com.kbbench.algorithm.impl.GuidedFilterColorDenoise
import com.kbbench.algorithm.impl.GuidedFilterDenoise
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GuidedFilterTest {

    /**
     * The load-bearing test for banding: a band only loads a `2 * radius` halo, so if that halo were
     * ever too small the interior rows would silently pick up truncated-window values.
     */
    @Test
    fun bandedOutputMatchesSingleBandOutput() {
        val width = 29
        val height = 41
        val src = noiseImage(width, height, seed = 11)

        for (radius in intArrayOf(1, 4, 8)) {
            val wholeGray = GuidedFilter.filterGray(src, width, height, radius, EPS, bandHeight = height)
            val bandedGray = GuidedFilter.filterGray(src, width, height, radius, EPS, bandHeight = 7)
            assertContentEquals(wholeGray, bandedGray, "gray variant diverged at radius=$radius")

            val wholeColor = GuidedFilter.filterColor(src, width, height, radius, EPS, bandHeight = height)
            val bandedColor = GuidedFilter.filterColor(src, width, height, radius, EPS, bandHeight = 7)
            assertContentEquals(wholeColor, bandedColor, "color variant diverged at radius=$radius")
        }
    }

    @Test
    fun bandHeightDoesNotChangeResultForAnyDivision() {
        val width = 13
        val height = 30
        val src = noiseImage(width, height, seed = 5)

        val reference = GuidedFilter.filterGray(src, width, height, radius = 3, eps = EPS, bandHeight = height)
        for (band in 1..height) {
            val actual = GuidedFilter.filterGray(src, width, height, radius = 3, eps = EPS, bandHeight = band)
            assertContentEquals(reference, actual, "bandHeight=$band diverged")
        }
    }

    @Test
    fun constantImageIsUnchanged() {
        val width = 20
        val height = 20
        val src = IntArray(width * height) { argb(255, 90, 140, 30) }

        assertContentEquals(src, GuidedFilter.filterGray(src, width, height, 4, EPS))
        assertContentEquals(src, GuidedFilter.filterColor(src, width, height, 4, EPS))
    }

    /**
     * As `eps` grows the coefficient `a` vanishes, leaving `q = mean(mean(I))` — the box filter
     * applied twice, not once.
     */
    @Test
    fun hugeEpsDegeneratesToTwiceAppliedBoxFilter() {
        val width = 24
        val height = 18
        val radius = 3
        val src = noiseImage(width, height, seed = 3)

        val actual = GuidedFilter.filterGray(src, width, height, radius, eps = 1e9)
        val expected = twiceBoxFiltered(src, width, height, radius)

        assertChannelsWithin(expected, actual, tolerance = 1)
    }

    /** With `eps` far below the local variance every `a` approaches 1 and the filter is a no-op. */
    @Test
    fun tinyEpsApproachesIdentity() {
        val width = 24
        val height = 18
        val src = noiseImage(width, height, seed = 4)

        val actual = GuidedFilter.filterGray(src, width, height, radius = 2, eps = 1e-3)

        assertChannelsWithin(src, actual, tolerance = 3)
    }

    @Test
    fun preservesStepEdgeThatBoxFilterSmears() {
        val width = 64
        val height = 16
        val radius = 4
        val edge = width / 2
        val low = 40
        val high = 210
        val src = IntArray(width * height) { i ->
            val v = if (i % width < edge) low else high
            argb(255, v, v, v)
        }

        val guided = GuidedFilter.filterGray(src, width, height, radius, eps = 100.0)
        val blurred = twiceBoxFiltered(src, width, height, radius)

        val row = height / 2
        val original = (high - low).toDouble()
        val guidedStep = channelStepAcrossEdge(guided, width, row, edge)
        val blurredStep = channelStepAcrossEdge(blurred, width, row, edge)

        assertTrue(
            guidedStep >= 0.5 * original,
            "guided filter smeared the edge: kept $guidedStep of $original",
        )
        assertTrue(
            blurredStep <= 0.25 * original,
            "box reference was expected to smear the edge, kept $blurredStep of $original",
        )
    }

    /** The actual claim being made: filtering a noisy frame moves it closer to the clean one. */
    @Test
    fun improvesPsnrAgainstCleanReference() {
        val width = 96
        val height = 72
        val clean = smoothImage(width, height)
        val noisy = withGaussianNoise(clean, sigma = 15.0, seed = 42)

        val noisyPsnr = calculateQualityMetrics(clean, noisy).psnr
        for (denoised in listOf(
            GuidedFilter.filterGray(noisy, width, height, radius = 4, eps = 900.0),
            GuidedFilter.filterColor(noisy, width, height, radius = 4, eps = 900.0),
        )) {
            val psnr = calculateQualityMetrics(clean, denoised).psnr
            assertTrue(psnr > noisyPsnr, "denoising did not improve PSNR: $noisyPsnr -> $psnr")
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

        for (output in listOf(
            GuidedFilterDenoise().process(input),
            GuidedFilterColorDenoise().process(input),
        )) {
            assertEquals(width, output.width)
            assertEquals(height, output.height)
            assertEquals(width * height, output.pixels.size)
            for (i in src.indices) {
                assertEquals(src[i] ushr 24, output.pixels[i] ushr 24, "alpha changed at index $i")
            }
        }
    }

    @Test
    fun rejectsDegenerateParameters() {
        val src = IntArray(16) { argb(255, 10, 10, 10) }

        assertFailsWith<IllegalArgumentException> { GuidedFilter.filterGray(src, 4, 4, radius = 0, eps = 1.0) }
        assertFailsWith<IllegalArgumentException> { GuidedFilter.filterGray(src, 4, 4, radius = 2, eps = 0.0) }
        assertFailsWith<IllegalArgumentException> { GuidedFilter.filterColor(src, 4, 4, radius = 2, eps = -1.0) }
        assertFailsWith<IllegalArgumentException> { GuidedFilter.filterGray(src, 5, 4, radius = 2, eps = 1.0) }

        assertFailsWith<IllegalArgumentException> { GuidedFilterDenoise(radius = 0) }
        assertFailsWith<IllegalArgumentException> { GuidedFilterDenoise(eps = 0.0) }
        assertFailsWith<IllegalArgumentException> { GuidedFilterColorDenoise(radius = -1) }
        assertFailsWith<IllegalArgumentException> { GuidedFilterColorDenoise(eps = -0.5) }
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun noiseImage(width: Int, height: Int, seed: Int): IntArray {
        val random = Random(seed)
        return IntArray(width * height) {
            argb(255, random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
    }

    /** Piecewise-smooth: two plateaus joined by a ramp, with a gentle vertical gradient. */
    private fun smoothImage(width: Int, height: Int): IntArray {
        val third = width / 3
        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val base = when {
                x < third -> 60
                x < 2 * third -> 60 + (x - third) * 140 / third
                else -> 200
            } + y * 20 / height
            argb(255, (base + 15).coerceIn(0, 255), base.coerceIn(0, 255), (base - 15).coerceIn(0, 255))
        }
    }

    private fun withGaussianNoise(src: IntArray, sigma: Double, seed: Long): IntArray {
        val random = java.util.Random(seed)
        return IntArray(src.size) { i ->
            val p = src[i]
            argb(
                p ushr 24,
                (((p shr 16) and 0xFF) + (random.nextGaussian() * sigma).toInt()).coerceIn(0, 255),
                (((p shr 8) and 0xFF) + (random.nextGaussian() * sigma).toInt()).coerceIn(0, 255),
                ((p and 0xFF) + (random.nextGaussian() * sigma).toInt()).coerceIn(0, 255),
            )
        }
    }

    private fun twiceBoxFiltered(src: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val out = IntArray(src.size)
        val plane = FloatArray(src.size)
        val scratch = FloatArray(src.size)
        for (shift in intArrayOf(16, 8, 0)) {
            for (i in src.indices) plane[i] = ((src[i] shr shift) and 0xFF).toFloat()
            BoxFilter.mean(plane, plane, scratch, width, height, radius)
            BoxFilter.mean(plane, plane, scratch, width, height, radius)
            for (i in src.indices) {
                out[i] = out[i] or ((plane[i] + 0.5f).toInt().coerceIn(0, 255) shl shift)
            }
        }
        for (i in src.indices) out[i] = out[i] or (src[i] and (0xFF shl 24))
        return out
    }

    private fun channelStepAcrossEdge(pixels: IntArray, width: Int, row: Int, edge: Int): Double {
        val left = (pixels[row * width + edge - 1] shr 16) and 0xFF
        val right = (pixels[row * width + edge] shr 16) and 0xFF
        return abs(right - left).toDouble()
    }

    private fun assertChannelsWithin(expected: IntArray, actual: IntArray, tolerance: Int) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                val e = (expected[i] shr shift) and 0xFF
                val a = (actual[i] shr shift) and 0xFF
                assertTrue(
                    abs(e - a) <= tolerance,
                    "index $i shift $shift: expected $e, got $a (tolerance $tolerance)",
                )
            }
        }
    }

    private companion object {
        /** 0.1^2 in the paper's normalized units, carried to the 8-bit scale. */
        const val EPS = 0.01 * 255.0 * 255.0
    }
}
