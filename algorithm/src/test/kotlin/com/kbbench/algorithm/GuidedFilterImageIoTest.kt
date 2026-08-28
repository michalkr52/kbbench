package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.ImageAlgorithm
import com.kbbench.algorithm.base.calculateQualityMetrics
import com.kbbench.algorithm.filter.GuidedFilter
import com.kbbench.algorithm.impl.GuidedFilterColorDenoise
import com.kbbench.algorithm.impl.GuidedFilterDenoise
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs both guided-filter variants over a real photo and leaves the filtered images plus a metrics
 * report in `build/test-output` for visual inspection.
 */
class GuidedFilterImageIoTest {

    @Test
    fun runsBothVariantsOnPngAndSavesOutput() {
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

        val before = computeMetrics(pixels)
        val report = StringBuilder()
        report.appendLine("GuidedFilter metrics")
        report.appendLine("input=${inputFile.path} (${width}x$height)")
        report.appendLine("before R: ${format(before.r)}")
        report.appendLine("before G: ${format(before.g)}")
        report.appendLine("before B: ${format(before.b)}")
        report.appendLine("before luma: mean=${"%.2f".format(before.lumaMean)} std=${"%.2f".format(before.lumaStd)}")

        val variants: List<Pair<String, ImageAlgorithm>> = listOf(
            "guided" to GuidedFilterDenoise(),
            "guided_color" to GuidedFilterColorDenoise(),
        )

        for ((slug, algorithm) in variants) {
            val output = algorithm.process(input)

            assertEquals(width, output.width)
            assertEquals(height, output.height)
            assertEquals(width * height, output.pixels.size)

            val after = computeMetrics(output.pixels)
            val quality = calculateQualityMetrics(referencePixels = pixels, candidatePixels = output.pixels)

            assertTrue(!quality.psnr.isNaN(), "${algorithm.name}: PSNR should be a valid number or +Infinity")
            assertTrue(quality.ssim in -1.0..1.0, "${algorithm.name}: SSIM out of range, got ${quality.ssim}")

            // Edge-preserving smoothing removes local detail, so luma spread must not grow.
            assertTrue(
                after.lumaStd <= before.lumaStd + 1e-6,
                "${algorithm.name}: luma std grew ${before.lumaStd} -> ${after.lumaStd}",
            )

            val outputFile = File("build/test-output/${slug}_output.png")
            writePng(output.pixels, width, height, outputFile)
            assertTrue(outputFile.exists(), "Brak pliku wyjsciowego: ${outputFile.path}")

            report.appendLine()
            report.appendLine("${algorithm.name} (${algorithm.metadata.description})")
            report.appendLine("  totalTime=${output.totalTime}ms")
            report.appendLine("  psnr=${"%.4f".format(quality.psnr)}  ssim=${"%.6f".format(quality.ssim)}")
            report.appendLine("  after R: ${format(after.r)}")
            report.appendLine("  after G: ${format(after.g)}")
            report.appendLine("  after B: ${format(after.b)}")
            report.appendLine(
                "  after luma: mean=${"%.2f".format(after.lumaMean)} std=${"%.2f".format(after.lumaStd)}"
            )
            report.appendLine("  output=${outputFile.path}")
        }

        // The automatic band height fits this photo in a single band, so force a split here too:
        // real images have large flat regions where the variance cancellation is most delicate.
        assertContentEquals(
            GuidedFilter.filterGray(pixels, width, height, radius = 4, eps = EPS_8BIT, bandHeight = height),
            GuidedFilter.filterGray(pixels, width, height, radius = 4, eps = EPS_8BIT, bandHeight = 64),
            "banded run over the real photo diverged from the single-band run",
        )

        val metricsFile = File("build/test-output/guided_metrics.txt")
        metricsFile.parentFile.mkdirs()
        metricsFile.writeText(report.toString())
        println(metricsFile.readText())

        assertTrue(metricsFile.exists(), "Brak pliku metryk: ${metricsFile.path}")
    }

    private companion object {
        /** The default `0.04^2` of [GuidedFilterDenoise], carried to the 8-bit scale. */
        const val EPS_8BIT = 0.04 * 0.04 * 255.0 * 255.0
    }
}
