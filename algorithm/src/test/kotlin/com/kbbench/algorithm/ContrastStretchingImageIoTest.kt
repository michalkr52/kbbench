package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.calculateQualityMetrics
import com.kbbench.algorithm.impl.ContrastStretching
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContrastStretchingImageIoTest {

    @Test
    fun runOnPngAndSaveOutput() {
        val resourcesDir = File("src/test/resources")
        resourcesDir.mkdirs()

        val requestedInput = File(resourcesDir, "input.png")
        val inputFile = if (requestedInput.exists()) {
            requestedInput
        } else {
            generateSyntheticInput(resourcesDir)
        }

        val outputFile = File("build/test-output/contrast_output.png")
        outputFile.parentFile.mkdirs()

        val image = ImageIO.read(inputFile)
        assertTrue(image != null, "Nie udalo sie odczytac obrazu: ${inputFile.path}")

        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)

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

        val outImg = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        outImg.setRGB(0, 0, width, height, output.pixels, 0, width)
        ImageIO.write(outImg, "png", outputFile)

        val metricsFile = File("build/test-output/contrast_metrics.txt")
        metricsFile.writeText(buildMetricsReport(inputFile, before, after, output.totalTime, quality.psnr, quality.ssim))
        println(metricsFile.readText())

        assertTrue(outputFile.exists(), "Brak pliku wyjsciowego: ${outputFile.path}")
        assertTrue(metricsFile.exists(), "Brak pliku metryk: ${metricsFile.path}")
    }

    private fun generateSyntheticInput(resourcesDir: File): File {
        val width = 256
        val height = 256
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (x * 180) / (width - 1) + 30
                val g = (y * 180) / (height - 1) + 30
                val b = (((x + y) * 180) / (width + height - 2)) + 30
                val a = 255
                val pixel = (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
                image.setRGB(x, y, pixel)
            }
        }

        val generated = File(resourcesDir, "generated_input.png")
        ImageIO.write(image, "png", generated)
        return generated
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

    private fun format(c: ChannelMetrics): String =
        "min=${c.min} max=${c.max} range=${c.range} mean=${"%.2f".format(c.mean)} std=${"%.2f".format(c.std)}"

    private fun computeMetrics(pixels: IntArray): ImageMetrics {
        var rMin = 255; var rMax = 0; var rSum = 0.0; var rSumSq = 0.0
        var gMin = 255; var gMax = 0; var gSum = 0.0; var gSumSq = 0.0
        var bMin = 255; var bMax = 0; var bSum = 0.0; var bSumSq = 0.0
        var lumaSum = 0.0; var lumaSumSq = 0.0

        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            if (r < rMin) rMin = r
            if (r > rMax) rMax = r
            if (g < gMin) gMin = g
            if (g > gMax) gMax = g
            if (b < bMin) bMin = b
            if (b > bMax) bMax = b

            rSum += r; rSumSq += r * r
            gSum += g; gSumSq += g * g
            bSum += b; bSumSq += b * b

            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            lumaSum += luma
            lumaSumSq += luma * luma
        }

        val n = pixels.size.toDouble()
        return ImageMetrics(
            r = stats(rMin, rMax, rSum, rSumSq, n),
            g = stats(gMin, gMax, gSum, gSumSq, n),
            b = stats(bMin, bMax, bSum, bSumSq, n),
            lumaMean = lumaSum / n,
            lumaStd = std(lumaSum, lumaSumSq, n),
        )
    }

    private fun stats(min: Int, max: Int, sum: Double, sumSq: Double, n: Double): ChannelMetrics {
        return ChannelMetrics(
            min = min,
            max = max,
            range = max - min,
            mean = sum / n,
            std = std(sum, sumSq, n),
        )
    }

    private fun std(sum: Double, sumSq: Double, n: Double): Double {
        val mean = sum / n
        val variance = (sumSq / n) - (mean * mean)
        return sqrt(variance.coerceAtLeast(0.0))
    }

    private data class ChannelMetrics(
        val min: Int,
        val max: Int,
        val range: Int,
        val mean: Double,
        val std: Double,
    )

    private data class ImageMetrics(
        val r: ChannelMetrics,
        val g: ChannelMetrics,
        val b: ChannelMetrics,
        val lumaMean: Double,
        val lumaStd: Double,
    )
}
