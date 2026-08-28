package com.kbbench.algorithm

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * Shared helpers for the image-IO tests, which read a real photo from `src/test/resources` and
 * write filtered output plus a metrics report under `build/test-output`.
 */

internal data class ChannelMetrics(
    val min: Int,
    val max: Int,
    val range: Int,
    val mean: Double,
    val std: Double,
)

internal data class ImageMetrics(
    val r: ChannelMetrics,
    val g: ChannelMetrics,
    val b: ChannelMetrics,
    val lumaMean: Double,
    val lumaStd: Double,
)

/** Reads `input.png` if the developer supplied one, otherwise generates a synthetic gradient. */
internal fun loadTestImage(): Pair<File, BufferedImage> {
    val resourcesDir = File("src/test/resources")
    resourcesDir.mkdirs()

    val requested = File(resourcesDir, "input.png")
    val file = if (requested.exists()) requested else generateSyntheticInput(resourcesDir)
    val image = ImageIO.read(file) ?: error("Nie udalo sie odczytac obrazu: ${file.path}")
    return file to image
}

internal fun generateSyntheticInput(resourcesDir: File): File {
    val width = 256
    val height = 256
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    for (y in 0 until height) {
        for (x in 0 until width) {
            val r = (x * 180) / (width - 1) + 30
            val g = (y * 180) / (height - 1) + 30
            val b = (((x + y) * 180) / (width + height - 2)) + 30
            val pixel = (255 shl 24) or
                (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or
                b.coerceIn(0, 255)
            image.setRGB(x, y, pixel)
        }
    }

    val generated = File(resourcesDir, "generated_input.png")
    ImageIO.write(image, "png", generated)
    return generated
}

internal fun readPixels(image: BufferedImage): IntArray {
    val pixels = IntArray(image.width * image.height)
    image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
    return pixels
}

internal fun writePng(pixels: IntArray, width: Int, height: Int, file: File) {
    file.parentFile?.mkdirs()
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    ImageIO.write(image, "png", file)
}

internal fun computeMetrics(pixels: IntArray): ImageMetrics {
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

internal fun format(c: ChannelMetrics): String =
    "min=${c.min} max=${c.max} range=${c.range} mean=${"%.2f".format(c.mean)} std=${"%.2f".format(c.std)}"

private fun stats(min: Int, max: Int, sum: Double, sumSq: Double, n: Double): ChannelMetrics =
    ChannelMetrics(
        min = min,
        max = max,
        range = max - min,
        mean = sum / n,
        std = std(sum, sumSq, n),
    )

private fun std(sum: Double, sumSq: Double, n: Double): Double {
    val mean = sum / n
    val variance = (sumSq / n) - (mean * mean)
    return sqrt(variance.coerceAtLeast(0.0))
}
