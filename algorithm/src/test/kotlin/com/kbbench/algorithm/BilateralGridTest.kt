package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.calculateQualityMetrics
import com.kbbench.algorithm.filter.BilateralGrid
import com.kbbench.algorithm.impl.FastBilateralDenoise
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BilateralGridTest {

    /**
     * Grid cells are indexed from absolute pixel coordinates, so a band boundary must not shift the
     * sampling lattice. Splatted rows within [KERNEL_RADIUS] of every sliced row are loaded, and if
     * that halo were short the interior rows would quietly lose part of their support.
     */
    @Test
    fun bandedOutputMatchesSingleBandOutput() {
        val width = 31
        val height = 44
        val src = noiseImage(width, height, seed = 13)

        for (sigmaSpatial in doubleArrayOf(1.0, 2.0, 3.5)) {
            val whole = filter(src, width, height, sigmaSpatial, bandHeight = height)
            for (band in intArrayOf(1, 2, 5, 8, 17, 43)) {
                assertContentEquals(
                    whole,
                    filter(src, width, height, sigmaSpatial, bandHeight = band),
                    "sigmaSpatial=$sigmaSpatial bandHeight=$band diverged",
                )
            }
        }
    }

    /**
     * Every pixel splats into the same cell, so after the blur the homogeneous ratio at that cell is
     * the original value: a bilateral filter cannot move a constant image.
     */
    @Test
    fun constantImageIsUnchanged() {
        val width = 20
        val height = 20
        val src = IntArray(width * height) { argb(255, 90, 140, 30) }

        assertContentEquals(src, filter(src, width, height, sigmaSpatial = 3.0))
    }

    /**
     * The claim the whole construction rests on: splatting, blurring and slicing a downsampled grid
     * approximates the brute-force bilateral filter of Tomasi and Manduchi. Paris and Durand's own
     * error figures are in this range; anything much worse would mean the grid is misaligned.
     */
    @Test
    fun approximatesBruteForceBilateral() {
        val width = 48
        val height = 36
        val sigmaSpatial = 3.0
        val sigmaRange = 25.5
        val src = withGaussianNoise(smoothImage(width, height), sigma = 12.0, seed = 31)

        val approximate = BilateralGrid.filter(src, width, height, sigmaSpatial, sigmaRange)
        val exact = bruteForceBilateral(src, width, height, sigmaSpatial, sigmaRange)

        val psnr = calculateQualityMetrics(referencePixels = exact, candidatePixels = approximate).psnr
        val meanError = meanChannelDifference(exact, approximate)

        assertTrue(psnr >= 30.0, "grid approximation only reached $psnr dB against brute force")
        assertTrue(meanError <= 4.0, "mean per-channel error against brute force was $meanError")
    }

    /**
     * With the range kernel wide enough to span the whole intensity axis the grid collapses to a
     * couple of depth cells and the filter degenerates to a plain spatial blur, which is the
     * bilateral filter's defining limit.
     */
    @Test
    fun hugeSigmaRangeSmoothsAcrossAnEdge() {
        val width = 64
        val height = 16
        val edge = width / 2
        val src = IntArray(width * height) { i ->
            val v = if (i % width < edge) 40 else 210
            argb(255, v, v, v)
        }

        val wide = BilateralGrid.filter(src, width, height, sigmaSpatial = 3.0, sigmaRange = 10_000.0)

        val step = channelStepAcrossEdge(wide, width, height / 2, edge)
        assertTrue(step <= 0.4 * (210 - 40), "range kernel did not degenerate to a blur, step was $step")
    }

    /** The point of the filter: an edge survives a spatial sigma that would otherwise blur it away. */
    @Test
    fun preservesStepEdgeThatAWideBlurWouldSmear() {
        val width = 64
        val height = 16
        val edge = width / 2
        val low = 40
        val high = 210
        val src = IntArray(width * height) { i ->
            val v = if (i % width < edge) low else high
            argb(255, v, v, v)
        }

        val filtered = BilateralGrid.filter(src, width, height, sigmaSpatial = 3.0, sigmaRange = 25.5)

        val step = channelStepAcrossEdge(filtered, width, height / 2, edge)
        assertTrue(step >= 0.8 * (high - low), "edge was smeared, kept $step of ${high - low}")
    }

    @Test
    fun improvesPsnrAgainstCleanReference() {
        val width = 96
        val height = 72
        val clean = smoothImage(width, height)
        val noisy = withGaussianNoise(clean, sigma = 15.0, seed = 42)

        val noisyPsnr = calculateQualityMetrics(clean, noisy).psnr
        val denoised = BilateralGrid.filter(noisy, width, height, sigmaSpatial = 3.0, sigmaRange = 38.0)
        val psnr = calculateQualityMetrics(clean, denoised).psnr

        assertTrue(psnr > noisyPsnr, "denoising did not improve PSNR: $noisyPsnr -> $psnr")
    }

    @Test
    fun preservesAlphaAndDimensions() {
        val width = 15
        val height = 9
        val random = kotlin.random.Random(8)
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

        val output = FastBilateralDenoise().process(input)

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

        assertFailsWith<IllegalArgumentException> { BilateralGrid.filter(src, 4, 4, 0.0, 25.0) }
        assertFailsWith<IllegalArgumentException> { BilateralGrid.filter(src, 4, 4, 3.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { BilateralGrid.filter(src, 5, 4, 3.0, 25.0) }

        assertFailsWith<IllegalArgumentException> { FastBilateralDenoise(sigmaSpatial = 0.0) }
        assertFailsWith<IllegalArgumentException> { FastBilateralDenoise(sigmaRange = -0.1) }
    }

    private fun filter(
        src: IntArray,
        width: Int,
        height: Int,
        sigmaSpatial: Double,
        bandHeight: Int = 0,
    ): IntArray = BilateralGrid.filter(src, width, height, sigmaSpatial, 25.5, bandHeight)

}
