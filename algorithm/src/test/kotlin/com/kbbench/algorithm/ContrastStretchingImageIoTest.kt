package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.calculateQualityMetrics
import com.kbbench.algorithm.impl.ContrastStretching
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContrastStretchingImageIoTest {

    @Test
    fun runOnPngAndSaveOutput() {
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

        val output = ContrastStretching().process(input)
        val after = computeMetrics(output.pixels)
        val quality = calculateQualityMetrics(referencePixels = pixels, candidatePixels = output.pixels)

        assertEquals(width, output.width)
        assertEquals(height, output.height)
        assertEquals(width * height, output.pixels.size)
        assertTrue(!quality.psnr.isNaN(), "PSNR should be a valid number or +Infinity")
        assertTrue(quality.ssim in -1.0..1.0, "SSIM should stay in [-1, 1], got ${quality.ssim}")

        // Contrast stretching should not shrink per-channel dynamic range.
        assertTrue(after.r.range >= before.r.range, "R range shrank: ${before.r.range} -> ${after.r.range}")
        assertTrue(after.g.range >= before.g.range, "G range shrank: ${before.g.range} -> ${after.g.range}")
        assertTrue(after.b.range >= before.b.range, "B range shrank: ${before.b.range} -> ${after.b.range}")

        val outputFile = File("build/test-output/contrast_output.png")
        writePng(output.pixels, width, height, outputFile)

        val metricsFile = File("build/test-output/contrast_metrics.txt")
        metricsFile.writeText(
            buildMetricsReport(inputFile, before, after, output.totalTime, quality.psnr, quality.ssim)
        )
        println(metricsFile.readText())

        assertTrue(outputFile.exists(), "Brak pliku wyjsciowego: ${outputFile.path}")
        assertTrue(metricsFile.exists(), "Brak pliku metryk: ${metricsFile.path}")
    }

    private fun buildMetricsReport(
        inputFile: File,
        before: ImageMetrics,
        after: ImageMetrics,
        totalTime: Long,
        psnr: Double,
        ssim: Double,
    ): String {
        return buildString {
            appendLine("ContrastStretching metrics")
            appendLine("input=${inputFile.path}")
            appendLine("totalTime=$totalTime")
            appendLine("psnr=$psnr")
            appendLine("ssim=$ssim")
            appendLine("R: ${format(before.r)} -> ${format(after.r)}")
            appendLine("G: ${format(before.g)} -> ${format(after.g)}")
            appendLine("B: ${format(before.b)} -> ${format(after.b)}")
            appendLine("Luma mean: ${"%.2f".format(before.lumaMean)} -> ${"%.2f".format(after.lumaMean)}")
            appendLine("Luma std:  ${"%.2f".format(before.lumaStd)} -> ${"%.2f".format(after.lumaStd)}")
        }
    }
}
