package com.kbbench.algorithm.filter

import kotlin.math.max
import kotlin.math.min

/**
 * Adaptive directional unsharp masking (A. Polesel, G. Ramponi and V. J. Mathews, "Image enhancement via adaptive unsharp masking").
 *
 * Equations, with the paper's numbering:
 * ```
 * (3) z_x = 2*x(n,m) - x(n,m-1) - x(n,m+1)          directional Laplacians
 * (4) z_y = 2*x(n,m) - x(n-1,m) - x(n+1,m)
 * (5) y   = x + A^T Z,   A = [a_x, a_y]^T,  Z = [z_x, z_y]^T
 * (9) g_y = g_x + A^T G, G = [g(z_x), g(z_y)]^T     g = 3x3 highpass of Fig. 2
 * (10) v  = 3x3 local variance of x
 * (12) alpha = alphaB if v < tau1, alphaDh if v < tau2, else alphaDl
 * (11) g_d = alpha * g_x                            desired local dynamics
 * (13) e   = g_d - g_y
 * (16) A(n,m+1) = A(n,m) + 2*mu*e * R^-1(n,m) * G(n,m)
 * (17) R(n,m)   = (1 - beta) * R(n,m-1) + beta * G(n,m) * G^T(n,m)
 * ```
 * `g` is a linear highpass rather than the local variance one might expect: linearity is what makes
 * (9) exact for a locally constant `A`, leaving the cost quadratic in `A` with a single minimum.
 *
 * ### Departures from the paper
 *
 * - The paper processes 8-bit grayscale. Here the adaptation runs on luma and the single correction
 *   is added to R, G and B alike, so luminance shifts without disturbing the colour differences.
 *   Adapting each channel separately would amplify chroma noise.
 * - The paper says only that `A` "is adapted along the rows" and indexes the update by `m`. Taken
 *   literally here: every row restarts from `A = 0`, `R = I`. Rows are therefore independent, so no
 *   state crosses a band boundary and banded output equals whole-image output.
 * - [RIDGE] is added to `R`'s diagonal before inverting; the paper is silent on this. In a flat area
 *   `G` vanishes and `R` decays with it, leaving `R^-1 G` unbounded.
 * - Gains are clamped to `[0, maxGain]`, bounding the transients Section III notes "while the
 *   recursions are moving from a detail zone to a smooth area".
 * - Border windows shrink rather than being padded, matching [BoxFilter].
 *
 * ### Banding
 *
 * Output rows `[y0, y1)` need `g(z_y)` there, hence `z_y` on `[y0 - 1, y1 + 1)`, hence luma on
 * `[y0 - 2, y1 + 2)`: a halo of [HALO] rows, independent of every parameter and the widest of the
 * five plane dependencies. Halo rows are computed but never read.
 */
object AdaptiveDirectionalUnsharpMask {

    /** Working-set budget used to pick a band height when the caller does not supply one. */
    private const val TARGET_WORKING_BYTES = 32L * 1024 * 1024
    private const val MIN_BAND_HEIGHT = 16

    /** luma, z_x, z_y, g_x, g_zx, g_zy, mean, meanSquare, scratch. */
    private const val PLANES = 9

    /** Rows of context each band needs on either side; see the banding note above. */
    private const val HALO = 2

    private const val ALPHA_MASK = 0xFF shl 24

    /** Centre tap of the 3x3 highpass `g` of Fig. 2; the eight neighbours are all `-1`. */
    private const val HIGHPASS_CENTER = 8.0f

    /**
     * Diagonal loading of `R` before inversion, in squared intensity units. Negligible against the
     * `1e6` and up that `R` reaches on real detail; it only bites where `G` approaches zero.
     */
    private const val RIDGE = 1.0

    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    /**
     * @param src ARGB_8888 pixels, row-major, `width * height` entries.
     * @param tau1 Variance below which a pixel counts as smooth; tracks the input's noise level.
     *   The paper reports `[30, 60]` for its 8-bit material.
     * @param tau2 Variance at or above which a pixel counts as high-contrast; must exceed [tau1].
     * @param alphaB Dynamics multiplier in smooth areas; `>= 1`, fixed at `1` by the paper.
     * @param alphaDl Multiplier in high-contrast areas; must exceed `1`.
     * @param alphaDh Multiplier in medium-contrast areas; must exceed [alphaDl].
     * @param mu Gauss-Newton step size, `>= 0`. `0` freezes the gains at zero, making the filter a
     *   pass-through.
     * @param beta Forgetting factor of Eq. (17), strictly inside `(0, 1)`.
     * @param maxGain Upper clamp on either scaling factor; must be positive.
     * @param bandHeight Rows produced per band; `0` picks a value from [TARGET_WORKING_BYTES].
     * @return a new ARGB_8888 array of the same dimensions, alpha copied from [src].
     * @throws IllegalArgumentException if the dimensions or any parameter fall outside the above.
     */
    fun sharpen(
        src: IntArray,
        width: Int,
        height: Int,
        tau1: Double,
        tau2: Double,
        alphaB: Double,
        alphaDl: Double,
        alphaDh: Double,
        mu: Double,
        beta: Double,
        maxGain: Double,
        bandHeight: Int = 0,
    ): IntArray {
        validate(src, width, height, tau1, tau2, alphaB, alphaDl, alphaDh, mu, beta, maxGain)

        val out = IntArray(src.size)
        val band = resolveBandHeight(bandHeight, width, height)
        val capacity = width * min(height, band + 2 * HALO)

        val luma = FloatArray(capacity)
        val zx = FloatArray(capacity)
        val zy = FloatArray(capacity)
        val gx = FloatArray(capacity)
        val gzx = FloatArray(capacity)
        val gzy = FloatArray(capacity)
        val mean = FloatArray(capacity)
        val meanSquare = FloatArray(capacity)
        val scratch = FloatArray(capacity)

        val retain = 1.0 - beta

        forEachBand(height, band) { y0, y1, s0, s1 ->
            val rows = s1 - s0
            val count = width * rows
            val base = s0 * width

            for (i in 0 until count) {
                val pixel = src[base + i]
                luma[i] = LUMA_R * ((pixel shr 16) and 0xFF) +
                    LUMA_G * ((pixel shr 8) and 0xFF) +
                    LUMA_B * (pixel and 0xFF)
            }

            directionalLaplacians(luma, zx, zy, width, rows)
            highpass(luma, gx, width, rows)
            highpass(zx, gzx, width, rows)
            highpass(zy, gzy, width, rows)

            for (i in 0 until count) meanSquare[i] = luma[i] * luma[i]
            BoxFilter.mean(meanSquare, meanSquare, scratch, width, rows, radius = 1)
            BoxFilter.mean(luma, mean, scratch, width, rows, radius = 1)

            for (y in y0 until y1) {
                // Eq. (16) is a recursion in m alone, so each row starts from a clean slate.
                var gainX = 0.0
                var gainY = 0.0
                var rXX = 1.0
                var rXY = 0.0
                var rYY = 1.0

                val srcRow = y * width
                val planeRow = (y - s0) * width

                for (x in 0 until width) {
                    val i = srcRow + x
                    val p = planeRow + x

                    val m = mean[p].toDouble()
                    val variance = max(meanSquare[p].toDouble() - m * m, 0.0)
                    val alpha = when {
                        variance < tau1 -> alphaB
                        variance < tau2 -> alphaDh
                        else -> alphaDl
                    }

                    val hx = gzx[p].toDouble()
                    val hy = gzy[p].toDouble()

                    // e = g_d - g_y = (alpha - 1) * g_x - A^T G, from Eq. (11) and (14).
                    val error = (alpha - 1.0) * gx[p] - (gainX * hx + gainY * hy)

                    // Eq. (5), written with A(n,m); Eq. (16) below then produces A(n,m+1).
                    val correction = gainX * zx[p] + gainY * zy[p]
                    val pixel = src[i]
                    out[i] = (pixel and ALPHA_MASK) or
                        (corrected(pixel, 16, correction) shl 16) or
                        (corrected(pixel, 8, correction) shl 8) or
                        corrected(pixel, 0, correction)

                    rXX = retain * rXX + beta * hx * hx
                    rXY = retain * rXY + beta * hx * hy
                    rYY = retain * rYY + beta * hy * hy

                    val a = rXX + RIDGE
                    val d = rYY + RIDGE
                    val invDet = 1.0 / (a * d - rXY * rXY)
                    val stepX = (d * hx - rXY * hy) * invDet
                    val stepY = (a * hy - rXY * hx) * invDet

                    val scale = 2.0 * mu * error
                    gainX = (gainX + scale * stepX).coerceIn(0.0, maxGain)
                    gainY = (gainY + scale * stepY).coerceIn(0.0, maxGain)
                }
            }
        }

        return out
    }

    /** Eq. (3) and (4). Missing neighbours replicate, so the correction vanishes at the border. */
    private fun directionalLaplacians(
        luma: FloatArray,
        zx: FloatArray,
        zy: FloatArray,
        width: Int,
        height: Int,
    ) {
        for (y in 0 until height) {
            val up = max(0, y - 1) * width
            val mid = y * width
            val down = min(height - 1, y + 1) * width
            for (x in 0 until width) {
                val center = luma[mid + x]
                zx[mid + x] = 2f * center - luma[mid + max(0, x - 1)] - luma[mid + min(width - 1, x + 1)]
                zy[mid + x] = 2f * center - luma[up + x] - luma[down + x]
            }
        }
    }

    /** The operator `g` of Fig. 2: centre [HIGHPASS_CENTER], all eight neighbours `-1`. */
    private fun highpass(src: FloatArray, dst: FloatArray, width: Int, height: Int) {
        for (y in 0 until height) {
            val up = max(0, y - 1) * width
            val mid = y * width
            val down = min(height - 1, y + 1) * width
            for (x in 0 until width) {
                val left = max(0, x - 1)
                val right = min(width - 1, x + 1)
                val neighbours = src[up + left] + src[up + x] + src[up + right] +
                    src[mid + left] + src[mid + right] +
                    src[down + left] + src[down + x] + src[down + right]
                dst[mid + x] = HIGHPASS_CENTER * src[mid + x] - neighbours
            }
        }
    }

    /** Adds the luma-domain [correction] to one channel, rounding to nearest and clamping. */
    private fun corrected(pixel: Int, shift: Int, correction: Double): Int =
        (((pixel shr shift) and 0xFF) + correction + 0.5).toInt().coerceIn(0, 255)

    private fun validate(
        src: IntArray,
        width: Int,
        height: Int,
        tau1: Double,
        tau2: Double,
        alphaB: Double,
        alphaDl: Double,
        alphaDh: Double,
        mu: Double,
        beta: Double,
        maxGain: Double,
    ) {
        require(width > 0 && height > 0) { "Image must be non-empty, got ${width}x$height" }
        require(src.size == width * height) {
            "Pixel array of ${src.size} does not match ${width}x$height"
        }
        require(tau1 >= 0.0) { "tau1 must be >= 0, got $tau1" }
        require(tau1 < tau2) { "tau1 must be < tau2, got $tau1 and $tau2" }
        require(alphaB >= 1.0) { "alphaB must be >= 1, got $alphaB" }
        require(alphaDl > 1.0) { "alphaDl must be > 1, got $alphaDl" }
        require(alphaDh > alphaDl) { "alphaDh must exceed alphaDl, got $alphaDh and $alphaDl" }
        require(mu >= 0.0) { "mu must be >= 0, got $mu" }
        require(beta > 0.0 && beta < 1.0) { "beta must lie in (0, 1), got $beta" }
        require(maxGain > 0.0) { "maxGain must be > 0, got $maxGain" }
    }

    private fun resolveBandHeight(requested: Int, width: Int, height: Int): Int {
        if (requested > 0) return min(requested, height)
        val affordableRows = TARGET_WORKING_BYTES / (PLANES.toLong() * Float.SIZE_BYTES * width)
        val band = affordableRows - 2L * HALO
        // Raise to the floor first, then cap: an image shorter than MIN_BAND_HEIGHT is one band.
        return band.coerceAtLeast(MIN_BAND_HEIGHT.toLong()).coerceAtMost(height.toLong()).toInt()
    }

    private inline fun forEachBand(
        height: Int,
        bandHeight: Int,
        action: (y0: Int, y1: Int, s0: Int, s1: Int) -> Unit,
    ) {
        var y0 = 0
        while (y0 < height) {
            val y1 = min(y0 + bandHeight, height)
            action(y0, y1, max(0, y0 - HALO), min(height, y1 + HALO))
            y0 = y1
        }
    }
}
