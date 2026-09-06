package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.calculateQualityMetrics
import com.kbbench.algorithm.filter.AdaptiveDirectionalUnsharpMask
import com.kbbench.algorithm.impl.AdaptiveUnsharpMasking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs adaptive unsharp masking over a real photo and leaves the sharpened image plus a metrics
 * report in `build/test-output` for visual inspection of halo and noise behaviour.
 *
 * PSNR and SSIM here are against the *input*: they say how far the filter moved the image, not how
 * good the result is. For quality scores see [SharpeningRestorationTest].
 */
class AdaptiveUnsharpMaskImageIoTest {

    @Test
    fun runsOnPngAndSavesOutput() {
        val (inputFile, image) = loadTestImage()
        val width = image.width
        val height = image.height
        val pixels = readPixels(image)

        val input = AlgorithmInput(
            frames = listOf(pixels),
            width = width,
            height = height,
            exposureTimes = listOf(10_000_000L),
            isoValues = listOf(100),
            captureTimeMs = 0L,
        )

        val algorithm = AdaptiveUnsharpMasking()
        val output = algorithm.process(input)

        assertEquals(width, output.width)
        assertEquals(height, output.height)
        assertEquals(width * height, output.pixels.size)

        val before = computeMetrics(pixels)
        val after = computeMetrics(output.pixels)
        val quality = calculateQualityMetrics(referencePixels = pixels, candidatePixels = output.pixels)

        assertTrue(!quality.psnr.isNaN(), "PSNR should be a valid number or +Infinity")
        assertTrue(quality.ssim in -1.0..1.0, "SSIM out of range, got ${quality.ssim}")

        // Sharpening adds detail rather than removing it, so luma spread must not shrink. This is
        // the exact opposite of the guided filter's check, and the two would fail each other's.
        assertTrue(
            after.lumaStd >= before.lumaStd,
            "luma std shrank ${before.lumaStd} -> ${after.lumaStd}",
        )

        val outputFile = File("build/test-output/aum_output.png")
        writePng(output.pixels, width, height, outputFile)
        assertTrue(outputFile.exists(), "Brak pliku wyjsciowego: ${outputFile.path}")

        // The automatic band height fits this photo in a single band, so force a split as well:
        // rows are independent by construction and this is the regression that keeps them so.
        assertContentEquals(
            sharpen(pixels, width, height, bandHeight = height),
            sharpen(pixels, width, height, bandHeight = 64),
            "banded run over the real photo diverged from the single-band run",
        )

        val report = buildString {
            appendLine("AdaptiveUnsharpMask metrics")
            appendLine("input=${inputFile.path} (${width}x$height)")
            appendLine("before R: ${format(before.r)}")
            appendLine("before G: ${format(before.g)}")
            appendLine("before B: ${format(before.b)}")
            appendLine("before luma: mean=${"%.2f".format(before.lumaMean)} std=${"%.2f".format(before.lumaStd)}")
            appendLine()
            appendLine("${algorithm.name} (${algorithm.metadata.description})")
            appendLine("  totalTime=${output.totalTime}ms")
            appendLine(
                "  departure from input (distance, not quality): " +
                    "psnr=${"%.4f".format(quality.psnr)} dB  ssim=${"%.6f".format(quality.ssim)}"
            )
            appendLine("  after R: ${format(after.r)}")
            appendLine("  after G: ${format(after.g)}")
            appendLine("  after B: ${format(after.b)}")
            appendLine("  after luma: mean=${"%.2f".format(after.lumaMean)} std=${"%.2f".format(after.lumaStd)}")
            appendLine("  output=${outputFile.path}")
        }

        val metricsFile = File("build/test-output/aum_metrics.txt")
        metricsFile.parentFile.mkdirs()
        metricsFile.writeText(report)
        println(report)

        assertTrue(metricsFile.exists(), "Brak pliku metryk: ${metricsFile.path}")
    }

    private fun sharpen(src: IntArray, width: Int, height: Int, bandHeight: Int): IntArray =
        AdaptiveDirectionalUnsharpMask.sharpen(
            src = src,
            width = width,
            height = height,
            tau1 = 60.0,
            tau2 = 200.0,
            alphaB = 1.0,
            alphaDl = 3.0,
            alphaDh = 4.0,
            mu = 0.1,
            beta = 0.5,
            maxGain = 4.0,
            bandHeight = bandHeight,
        )
}
