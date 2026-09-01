package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.calculateQualityMetrics
import com.kbbench.algorithm.impl.AdaptiveUnsharpMasking
import com.kbbench.algorithm.impl.LinearUnsharpMasking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs classical unsharp masking over a real photo, leaves the result in `build/test-output` for
 * inspection, and puts its numbers next to the adaptive variant's on the same frame — the
 * comparison the linear baseline exists to provide.
 *
 * PSNR and SSIM here are against the *input*: they say how far a filter moved the image, not how
 * good the result is. For quality scores see [SharpeningRestorationTest].
 */
class LinearUnsharpMaskImageIoTest {

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

        val algorithm = LinearUnsharpMasking()
        val output = algorithm.process(input)

        assertEquals(width, output.width)
        assertEquals(height, output.height)
        assertEquals(width * height, output.pixels.size)

        val before = computeMetrics(pixels)
        val after = computeMetrics(output.pixels)
        val quality = calculateQualityMetrics(referencePixels = pixels, candidatePixels = output.pixels)

        assertTrue(!quality.psnr.isNaN(), "PSNR should be a valid number or +Infinity")
        assertTrue(quality.ssim in -1.0..1.0, "SSIM out of range, got ${quality.ssim}")
        assertTrue(
            after.lumaStd >= before.lumaStd,
            "sharpening shrank the luma spread: ${before.lumaStd} -> ${after.lumaStd}",
        )

        val outputFile = File("build/test-output/linear_um_output.png")
        writePng(output.pixels, width, height, outputFile)

        // Same frame through the adaptive variant, so both sets of numbers land in one report.
        val adaptive = AdaptiveUnsharpMasking().process(input)
        val adaptiveAfter = computeMetrics(adaptive.pixels)
        val adaptiveQuality =
            calculateQualityMetrics(referencePixels = pixels, candidatePixels = adaptive.pixels)

        val metricsFile = File("build/test-output/linear_um_metrics.txt")
        metricsFile.parentFile.mkdirs()
        metricsFile.writeText(
            buildString {
                appendLine("LinearUnsharpMask metrics")
                appendLine("input=${inputFile.path} (${width}x$height)")
                appendLine("lambda=0.5 (Table I, linear UM)")
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
                appendLine("Fixed gain vs adapted gain on the same frame")
                appendLine("(psnr/ssim are distance from the input, so lower = moved the image more):")
                appendLine(
                    "  ${algorithm.name}: ${output.totalTime}ms  " +
                        "psnr=${"%.4f".format(quality.psnr)}  ssim=${"%.6f".format(quality.ssim)}  " +
                        "luma std=${"%.2f".format(after.lumaStd)}"
                )
                appendLine(
                    "  AdaptiveUnsharpMask: ${adaptive.totalTime}ms  " +
                        "psnr=${"%.4f".format(adaptiveQuality.psnr)}  " +
                        "ssim=${"%.6f".format(adaptiveQuality.ssim)}  " +
                        "luma std=${"%.2f".format(adaptiveAfter.lumaStd)}"
                )
                appendLine("  input luma std=${"%.2f".format(before.lumaStd)}")
                appendLine("output=${outputFile.path}")
            }
        )
        println(metricsFile.readText())

        assertTrue(outputFile.exists(), "Brak pliku wyjsciowego: ${outputFile.path}")
        assertTrue(metricsFile.exists(), "Brak pliku metryk: ${metricsFile.path}")
    }
}
