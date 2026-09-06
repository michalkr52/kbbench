package com.kbbench.algorithm

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Synthetic images, pixel assertions and reference implementations shared by the filter tests.
 * Anything specific to one algorithm's claims stays in that algorithm's test class; what lives here
 * is used by at least two.
 */

internal fun argb(a: Int, r: Int, g: Int, b: Int): Int =
    (a shl 24) or (r shl 16) or (g shl 8) or b

internal fun noiseImage(width: Int, height: Int, seed: Int): IntArray {
    val random = Random(seed)
    return IntArray(width * height) {
        argb(255, random.nextInt(256), random.nextInt(256), random.nextInt(256))
    }
}

/** Piecewise-smooth: two plateaus joined by a ramp, with a gentle vertical gradient. */
internal fun smoothImage(width: Int, height: Int): IntArray {
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

internal fun withGaussianNoise(src: IntArray, sigma: Double, seed: Long): IntArray {
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

/**
 * Separable Gaussian blur, the known degradation in restoration tests.
 *
 * Gaussian rather than a box mean because a box of radius 1 has exactly the 3x3 support of both
 * unsharp-masking operators, which hands the fixed-gain filter a degradation it is built to invert
 * and flatters it for a reason that has nothing to do with adaptation.
 *
 * Borders replicate, matching how the sharpeners treat them. The kernel is truncated at three
 * standard deviations and renormalized, so it sums to one regardless.
 *
 * @param sigma Standard deviation in pixels; must be positive.
 * @return a new ARGB_8888 array of the same dimensions, alpha copied from [src].
 */
internal fun gaussianBlurred(src: IntArray, width: Int, height: Int, sigma: Double): IntArray {
    require(sigma > 0.0) { "sigma must be > 0, got $sigma" }

    val radius = ceil(3.0 * sigma).toInt()
    val kernel = DoubleArray(2 * radius + 1) { i ->
        val d = (i - radius).toDouble()
        exp(-(d * d) / (2.0 * sigma * sigma))
    }
    val sum = kernel.sum()
    for (i in kernel.indices) kernel[i] /= sum

    val out = IntArray(src.size)
    val plane = DoubleArray(src.size)
    val scratch = DoubleArray(src.size)

    for (shift in intArrayOf(16, 8, 0)) {
        for (i in src.indices) plane[i] = ((src[i] shr shift) and 0xFF).toDouble()

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var acc = 0.0
                for (k in kernel.indices) {
                    acc += kernel[k] * plane[row + (x + k - radius).coerceIn(0, width - 1)]
                }
                scratch[row + x] = acc
            }
        }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var acc = 0.0
                for (k in kernel.indices) {
                    acc += kernel[k] * scratch[(y + k - radius).coerceIn(0, height - 1) * width + x]
                }
                out[row + x] = out[row + x] or ((acc + 0.5).toInt().coerceIn(0, 255) shl shift)
            }
        }
    }
    for (i in src.indices) out[i] = out[i] or (src[i] and (0xFF shl 24))
    return out
}

/** Size of the red-channel jump between the two columns straddling [edge]. */
internal fun channelStepAcrossEdge(pixels: IntArray, width: Int, row: Int, edge: Int): Double {
    val left = (pixels[row * width + edge - 1] shr 16) and 0xFF
    val right = (pixels[row * width + edge] shr 16) and 0xFF
    return abs(right - left).toDouble()
}

internal fun assertChannelsWithin(expected: IntArray, actual: IntArray, tolerance: Int) {
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

/**
 * Tomasi and Manduchi's bilateral filter evaluated directly from the definition, as the reference
 * `BilateralGrid` is checked against.
 *
 * Uses the same luma-driven shared weight as the grid so the two are comparable, and truncates the
 * spatial kernel at [BILATERAL_KERNEL_RADIUS] standard deviations to match the reach of the grid's
 * five-tap blur. Cost is O(sigmaSpatial^2) per pixel, so keep the images small.
 */
internal fun bruteForceBilateral(
    src: IntArray,
    width: Int,
    height: Int,
    sigmaSpatial: Double,
    sigmaRange: Double,
): IntArray {
    val radius = ceil(BILATERAL_KERNEL_RADIUS * sigmaSpatial).toInt()
    val spatialDenominator = 2.0 * sigmaSpatial * sigmaSpatial
    val rangeDenominator = 2.0 * sigmaRange * sigmaRange
    val out = IntArray(src.size)

    for (y in 0 until height) {
        for (x in 0 until width) {
            val centre = src[y * width + x]
            val centreLuma = testLuma(centre)

            var sumWeight = 0.0
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0

            for (dy in -radius..radius) {
                val ny = y + dy
                if (ny < 0 || ny >= height) continue
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx < 0 || nx >= width) continue

                    val neighbour = src[ny * width + nx]
                    val spatial = exp(-(dx * dx + dy * dy) / spatialDenominator)
                    val delta = centreLuma - testLuma(neighbour)
                    val weight = spatial * exp(-(delta * delta) / rangeDenominator)

                    sumWeight += weight
                    sumR += weight * ((neighbour shr 16) and 0xFF)
                    sumG += weight * ((neighbour shr 8) and 0xFF)
                    sumB += weight * (neighbour and 0xFF)
                }
            }

            out[y * width + x] = argb(
                centre ushr 24,
                ((sumR / sumWeight) + 0.5).toInt().coerceIn(0, 255),
                ((sumG / sumWeight) + 0.5).toInt().coerceIn(0, 255),
                ((sumB / sumWeight) + 0.5).toInt().coerceIn(0, 255),
            )
        }
    }
    return out
}

/** Matches the reach of the grid's `[1, 4, 6, 4, 1]` blur, in standard deviations. */
internal const val BILATERAL_KERNEL_RADIUS = 2

private fun testLuma(pixel: Int): Double =
    0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) + 0.114 * (pixel and 0xFF)

/** Extracts a centred [size] x [size] crop, for references too costly to run on a full frame. */
internal fun centreCrop(src: IntArray, width: Int, height: Int, size: Int): IntArray {
    val x0 = (width - size) / 2
    val y0 = (height - size) / 2
    return IntArray(size * size) { i ->
        src[(y0 + i / size) * width + x0 + (i % size)]
    }
}

/** Mean absolute per-channel difference, a gentler summary than a worst-case bound. */
internal fun meanChannelDifference(expected: IntArray, actual: IntArray): Double {
    assertEquals(expected.size, actual.size)
    var total = 0.0
    for (i in expected.indices) {
        for (shift in intArrayOf(16, 8, 0)) {
            total += abs(((expected[i] shr shift) and 0xFF) - ((actual[i] shr shift) and 0xFF))
        }
    }
    return total / (expected.size * 3.0)
}
