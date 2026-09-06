package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.calculateQualityMetrics
import com.kbbench.algorithm.filter.BilateralGrid
import com.kbbench.algorithm.impl.FastBilateralDenoise
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the fast bilateral filter over a real photo, leaves the result in `build/test-output` for
 * inspection, and measures the grid approximation against the brute-force definition on a crop —
 * the number worth quoting when comparing this filter with the guided filter.
 *
 * The two PSNR figures mean different things: against the input it measures how far the filter moved
 * the image and is not a quality score, while against brute force it is a genuine accuracy measure,
 * the reference there being the correct answer. For denoising quality see [DenoiseGroundTruthTest].
 */
class BilateralImageIoTest {

    @Test
    fun runsOnPngAndSavesOutput() {
        val (inputFile, image) = loadTestImage()
        val width = image.width
        val height = image.height
        val pixels = readPixels(image)

        val algorithm = FastBilateralDenoise()
        val output = algorithm.process(
            AlgorithmInput(
                frames = listOf(pixels),
                width = width,
                height = height,
                exposureTimes = listOf(10_000_000L),
                isoValues = listOf(100),
                captureTimeMs = 0L,
            )
        )

        assertEquals(width, output.width)
        assertEquals(height, output.height)
        assertEquals(width * height, output.pixels.size)

        val before = computeMetrics(pixels)
        val after = computeMetrics(output.pixels)
        val quality = calculateQualityMetrics(referencePixels = pixels, candidatePixels = output.pixels)

        assertTrue(!quality.psnr.isNaN(), "PSNR should be a valid number or +Infinity")
        assertTrue(quality.ssim in -1.0..1.0, "SSIM out of range, got ${quality.ssim}")
        assertTrue(
            after.lumaStd <= before.lumaStd + 1e-6,
            "edge-preserving smoothing grew the luma spread: ${before.lumaStd} -> ${after.lumaStd}",
        )

        val outputFile = File("build/test-output/bilateral_output.png")
        writePng(output.pixels, width, height, outputFile)

        // Brute force is O(sigmaSpatial^2) per pixel, so the fidelity check runs on a crop.
        val cropSize = minOf(CROP_SIZE, width, height)
        val crop = centreCrop(pixels, width, height, cropSize)
        val approximate = BilateralGrid.filter(crop, cropSize, cropSize, SIGMA_SPATIAL, SIGMA_RANGE)
        val exact = bruteForceBilateral(crop, cropSize, cropSize, SIGMA_SPATIAL, SIGMA_RANGE)
        val fidelity = calculateQualityMetrics(referencePixels = exact, candidatePixels = approximate)

        val metricsFile = File("build/test-output/bilateral_metrics.txt")
        metricsFile.parentFile.mkdirs()
        metricsFile.writeText(
            buildString {
                appendLine("FastBilateral metrics")
                appendLine("input=${inputFile.path} (${width}x$height)")
                appendLine("filtered at the algorithm defaults; fidelity check below uses its own sigmas")
                appendLine("totalTime=${output.totalTime}ms")
                appendLine(
                    "departure from input (distance, not quality): " +
                        "psnr=${"%.4f".format(quality.psnr)} dB  ssim=${"%.6f".format(quality.ssim)}"
                )
                appendLine("R: ${format(before.r)} -> ${format(after.r)}")
                appendLine("G: ${format(before.g)} -> ${format(after.g)}")
                appendLine("B: ${format(before.b)} -> ${format(after.b)}")
                appendLine(
                    "Luma: mean ${"%.2f".format(before.lumaMean)} -> ${"%.2f".format(after.lumaMean)}, " +
                        "std ${"%.2f".format(before.lumaStd)} -> ${"%.2f".format(after.lumaStd)}"
                )
                appendLine()
                appendLine(
                    "Grid approximation vs brute force, ${cropSize}x$cropSize centre crop, " +
                        "sigmaSpatial=$SIGMA_SPATIAL sigmaRange=$SIGMA_RANGE:"
                )
                appendLine("  psnr=${"%.4f".format(fidelity.psnr)} dB  ssim=${"%.6f".format(fidelity.ssim)}")
                appendLine("  mean per-channel error=${"%.4f".format(meanChannelDifference(exact, approximate))}")
                appendLine("output=${outputFile.path}")
            }
        )
        println(metricsFile.readText())

        assertTrue(outputFile.exists(), "Brak pliku wyjsciowego: ${outputFile.path}")
        assertTrue(metricsFile.exists(), "Brak pliku metryk: ${metricsFile.path}")
    }

    private companion object {
        const val CROP_SIZE = 256

        /**
         * Deliberately below [FastBilateralDenoise]'s default: the brute-force reference costs
         * `O(sigmaSpatial^2)` per pixel, and the paper's own `16` would mean 4225 taps per pixel.
         */
        const val SIGMA_SPATIAL = 3.0

        /** [FastBilateralDenoise]'s default `0.1`, on the 8-bit scale. */
        const val SIGMA_RANGE = 0.1 * 255.0
    }
}
