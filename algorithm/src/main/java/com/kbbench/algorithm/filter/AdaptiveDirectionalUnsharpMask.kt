package com.kbbench.algorithm.filter

import kotlin.math.max
import kotlin.math.min

/**
 * Adaptive directional unsharp masking (Polesel, Ramponi, Mathews, IEEE TIP 9(3), 2000).
 *
 * Classical unsharp masking adds a fixed multiple of a highpass response back to the image, which
 * amplifies noise in flat areas and overshoots on strong edges. This method replaces the constant
 * gain with a two-element vector adapted per pixel, so that smooth areas are left alone,
 * medium-contrast detail is emphasized most, and high-contrast edges are only moderately enhanced.
 *
 * ### The correction signal
 *
 * Two one-dimensional Laplacians, Eq. (3) and (4), one per axis:
 * ```
 * z_x(n,m) = 2*x(n,m) - x(n,m-1) - x(n,m+1)     // along columns
 * z_y(n,m) = 2*x(n,m) - x(n-1,m) - x(n+1,m)     // along rows
 * ```
 * and the output of Eq. (5)/(8) is `y = x + A^T Z`, with `A = [a_x, a_y]^T` the scaling vector and
 * `Z = [z_x, z_y]^T`. The two directions carry separate gains because the eye is anisotropic in its
 * sensitivity to detail of different orientations.
 *
 * ### Why the activity measure is a linear operator
 *
 * Local dynamics are measured with [HIGHPASS_CENTER]-weighted 3x3 highpass `g` of Fig. 2, not with a
 * local variance. That choice is what makes the whole scheme tractable: `g` being *linear* means
 * ```
 * g_y = g_x + A^T G,   G = [g(z_x), g(z_y)]^T                          Eq. (9), (14), (15)
 * ```
 * holds exactly for a locally constant `A`, so the error is affine in `A` and the cost `E[e^2]` is a
 * quadratic with a single minimum. A variance-based measure would be quadratic in `A` and lose that.
 *
 * Eq. (14) is the same identity used as a causal *approximation*: the exact `g_y` would need output
 * pixels to the right of and below the current one, whose gains have not been computed yet, so the
 * paper assumes `A` varies slowly and evaluates `g_y` with the gains in hand.
 *
 * ### The target, and where the three-way behaviour comes from
 *
 * A 3x3 local variance, Eq. (10), classifies the pixel, and the desired dynamics are a multiple of
 * the *input's own* dynamics, Eq. (11)/(12):
 * ```
 * g_d = alpha * g_x,   alpha = alphaB  (= 1) if v <  tau1     smooth   -> leave alone
 *                              alphaDh (= 4) if v <  tau2     medium   -> emphasize most
 *                              alphaDl (= 3) otherwise        high     -> emphasize moderately
 * ```
 * Note that no gate multiplies the gains: `alpha = 1` in a flat area means "keep the dynamics as
 * they are", and the adaptation drives the gains to zero there on its own.
 *
 * ### Adaptation
 *
 * Gauss-Newton along a row, Eq. (16) and (17):
 * ```
 * A(n,m+1) = A(n,m) + 2 * mu * e(n,m) * R^-1(n,m) * G(n,m)
 * R(n,m)   = (1 - beta) * R(n,m-1) + beta * G(n,m) * G^T(n,m)
 * ```
 * `R` is a symmetric 2x2, so its inverse is closed-form and costs a single division — matching the
 * paper's quoted budget of nineteen multiplications and one division per output sample.
 *
 * ### Departures from the paper, and why
 *
 * - The paper processes 8-bit grayscale. Here the entire adaptation runs on luma and the single
 *   resulting correction is added to R, G and B alike, which shifts luminance without disturbing the
 *   colour differences. Adapting each channel separately would amplify chroma noise.
 * - The paper says only that `A` "is adapted along the rows" and indexes the update by `m`. This
 *   implementation takes that literally: every row is an independent adaptation run starting from
 *   `A = 0` and `R = I`. Rows are therefore independent, which removes any state crossing a band
 *   boundary and makes banded output identical to whole-image output.
 * - [RIDGE] is added to `R`'s diagonal before inverting. In a flat area `G` vanishes and `R` decays
 *   to zero with it; `R^-1 G` would then be dominated by whatever noise survived. The paper is
 *   silent on this.
 * - Gains are clamped to `[0, maxGain]`. Section III notes transients "while the recursions are
 *   moving from a detail zone to a smooth area" that amplify the input noise; the clamp bounds them.
 * - Border windows shrink rather than being padded, the convention [BoxFilter] already uses, so the
 *   3x3 variance near an edge is normalized by the pixels actually inside the image.
 *
 * All planes are ARGB_8888 packed into [Int] in row-major order, and alpha is preserved.
 *
 * ### Banding
 *
 * Producing output rows `[y0, y1)` needs `g(z_y)` there, which needs `z_y` on `[y0 - 1, y1 + 1)`,
 * which needs luma on `[y0 - 2, y1 + 2)` — a halo of exactly [HALO] rows, independent of every
 * parameter. That is the widest of the five plane dependencies; `g(x)`, `g(z_x)` and the variance
 * all reach only one row out. Rows inside the halo are computed but never read, so the truncation at
 * a band edge is harmless, and where a band abuts the real image edge the truncation is the intended
 * shrink-window behaviour.
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
     * Diagonal loading of `R` before inversion, in squared intensity units.
     *
     * `G` reaches magnitudes in the thousands on real detail, so `R` there is of order `1e6` and up
     * and this is numerically invisible. It only matters where `G` approaches zero, and there it
     * bounds `R^-1 G` instead of letting it grow without limit.
     */
    private const val RIDGE = 1.0

    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    /**
     * Sharpens [src] and returns a new ARGB_8888 array.
     *
     * @param tau1 Variance below which a pixel counts as smooth. Tracks the input's noise level;
     *   the paper reports values in `[30, 60]` for its 8-bit test material.
     * @param tau2 Variance at or above which a pixel counts as high-contrast.
     * @param alphaB Desired dynamics multiplier in smooth areas. The paper fixes this at `1`, i.e.
     *   no enhancement at all.
     * @param alphaDl Multiplier in high-contrast areas; must exceed `1`.
     * @param alphaDh Multiplier in medium-contrast areas; must exceed [alphaDl], which is what makes
     *   medium-contrast detail the most strongly emphasized.
     * @param mu Gauss-Newton step size. `0` freezes the gains at zero and turns the filter into a
     *   pass-through, which is what makes the adaptation's contribution separately observable.
     * @param beta Forgetting factor of the autocorrelation recursion, strictly inside `(0, 1)`.
     * @param maxGain Upper clamp on either scaling factor. For reference, the paper's linear-UM
     *   baseline uses a fixed gain of `0.5`.
     * @param bandHeight Rows produced per band; `0` picks a value from [TARGET_WORKING_BYTES].
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
