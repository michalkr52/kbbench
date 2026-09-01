package com.kbbench.algorithm.filter

import kotlin.math.max
import kotlin.math.min

/**
 * Guided image filtering, He, Sun and Tang (ECCV 2010), in the self-guided form `I = p`.
 * [filterGray] implements Algorithm 1 per colour channel, [filterColor] the colour guidance of
 * Eq. 19.
 *
 * Departures from the paper:
 * - Images are processed in horizontal bands to bound memory; see [forEachBand] for the halo rule.
 * - The local variance is clamped to zero before use. `corr - mean^2` evaluated from [Float] planes
 *   can come out slightly negative in a flat region, which would make `a` negative or produce a NaN.
 * - Output is rounded rather than truncated. Truncation would darken every channel by half a level
 *   on average.
 * - Border windows shrink instead of being padded, the convention [BoxFilter] uses.
 */
object GuidedFilter {

    private const val TARGET_WORKING_BYTES = 32L * 1024 * 1024
    private const val MIN_BAND_HEIGHT = 16

    /** Simultaneously live [FloatArray] planes in [filterGray] and [filterColor] respectively. */
    private const val PLANES_GRAY = 4
    private const val PLANES_COLOR = 11

    private const val ALPHA_MASK = 0xFF shl 24

    private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)

    /**
     * Channel pairs of the symmetric covariance, whose six entries are stored in the order
     * `[rr, rg, rb, gg, gb, bb]`.
     */
    private val COVARIANCE_PAIRS = arrayOf(
        intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 2),
        intArrayOf(1, 1), intArrayOf(1, 2), intArrayOf(2, 2),
    )

    /**
     * Filters each colour channel independently, guided by itself.
     *
     * With `p = I`, Algorithm 1's `cov_Ip` is `var_I` and `mean_p` is `mean_I`, so the coefficients
     * reduce to `a = var / (var + eps)` and `b = mean_I * (1 - a)`. That is an exact rewrite, which
     * is why the code does not mirror the paper's general form.
     *
     * @param src ARGB_8888 pixels, row-major, exactly `width * height` entries.
     * @param radius Window half-width in pixels, at least 1.
     * @param eps Regularization on the 8-bit intensity scale, strictly positive.
     * @param bandHeight Rows produced per band; `0` derives one from [TARGET_WORKING_BYTES].
     * @return a new ARGB_8888 array of the same dimensions, alpha copied from [src].
     * @throws IllegalArgumentException if the dimensions disagree with [src], or a parameter is out
     *   of range.
     */
    fun filterGray(
        src: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        eps: Double,
        bandHeight: Int = 0,
    ): IntArray {
        validate(src, width, height, radius, eps)

        val out = IntArray(src.size)
        val band = resolveBandHeight(bandHeight, width, height, radius, PLANES_GRAY)
        val capacity = width * bandCapacityRows(band, height, radius)

        val guidance = FloatArray(capacity)
        val bufA = FloatArray(capacity)
        val bufB = FloatArray(capacity)
        val scratch = FloatArray(capacity)

        forEachBand(height, radius, band) { y0, y1, s0, s1 ->
            val rows = s1 - s0
            val count = width * rows
            val base = s0 * width

            for (shift in CHANNEL_SHIFTS) {
                for (i in 0 until count) {
                    guidance[i] = ((src[base + i] shr shift) and 0xFF).toFloat()
                }

                for (i in 0 until count) {
                    val v = guidance[i]
                    bufB[i] = v * v
                }
                BoxFilter.mean(bufB, bufB, scratch, width, rows, radius)
                BoxFilter.mean(guidance, bufA, scratch, width, rows, radius)

                // One sweep, because b reads the mean that a's own buffer would overwrite.
                for (i in 0 until count) {
                    val m = bufA[i].toDouble()
                    val variance = max(bufB[i].toDouble() - m * m, 0.0)
                    val a = variance / (variance + eps)
                    bufB[i] = a.toFloat()
                    bufA[i] = (m * (1.0 - a)).toFloat()
                }

                BoxFilter.mean(bufB, bufB, scratch, width, rows, radius)
                BoxFilter.mean(bufA, bufA, scratch, width, rows, radius)

                for (y in y0 until y1) {
                    val srcRow = y * width
                    val planeRow = (y - s0) * width
                    for (x in 0 until width) {
                        val i = srcRow + x
                        val p = planeRow + x
                        val intensity = ((src[i] shr shift) and 0xFF).toFloat()
                        val q = bufB[p] * intensity + bufA[p]
                        out[i] = out[i] or (round(q) shl shift)
                    }
                }
            }

            for (y in y0 until y1) {
                val row = y * width
                for (x in 0 until width) {
                    val i = row + x
                    out[i] = out[i] or (src[i] and ALPHA_MASK)
                }
            }
        }

        return out
    }

    /**
     * Filters using the full RGB frame as guidance, Eq. 19.
     *
     * Self-guidance makes `cov_Ip` for output channel `c` the `c`-th column of `Sigma`, and with
     * `M = (Sigma + eps * U)^-1` the per-pixel solution collapses to `A = I3 - eps * M` and
     * `b = eps * M * mean_I`. Both are symmetric, so six and three planes hold them, and both
     * overwrite the buffers they are derived from.
     *
     * @param src ARGB_8888 pixels, row-major, exactly `width * height` entries.
     * @param radius Window half-width in pixels, at least 1.
     * @param eps Regularization on the 8-bit intensity scale, strictly positive.
     * @param bandHeight Rows produced per band; `0` derives one from [TARGET_WORKING_BYTES].
     * @return a new ARGB_8888 array of the same dimensions, alpha copied from [src].
     * @throws IllegalArgumentException if the dimensions disagree with [src], or a parameter is out
     *   of range.
     */
    fun filterColor(
        src: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        eps: Double,
        bandHeight: Int = 0,
    ): IntArray {
        validate(src, width, height, radius, eps)

        val out = IntArray(src.size)
        val band = resolveBandHeight(bandHeight, width, height, radius, PLANES_COLOR)
        val capacity = width * bandCapacityRows(band, height, radius)

        // meanI[c] later holds b[c] and covariance[k] later holds A[k]; see solveCoefficients.
        val meanI = Array(3) { FloatArray(capacity) }
        val covariance = Array(6) { FloatArray(capacity) }
        val plane = FloatArray(capacity)
        val scratch = FloatArray(capacity)

        forEachBand(height, radius, band) { y0, y1, s0, s1 ->
            val rows = s1 - s0
            val count = width * rows
            val base = s0 * width

            for (c in 0 until 3) {
                val shift = CHANNEL_SHIFTS[c]
                for (i in 0 until count) {
                    plane[i] = ((src[base + i] shr shift) and 0xFF).toFloat()
                }
                BoxFilter.mean(plane, meanI[c], scratch, width, rows, radius)
            }

            for (k in COVARIANCE_PAIRS.indices) {
                val shiftJ = CHANNEL_SHIFTS[COVARIANCE_PAIRS[k][0]]
                val shiftL = CHANNEL_SHIFTS[COVARIANCE_PAIRS[k][1]]
                for (i in 0 until count) {
                    val pixel = src[base + i]
                    plane[i] = (((pixel shr shiftJ) and 0xFF) * ((pixel shr shiftL) and 0xFF)).toFloat()
                }
                BoxFilter.mean(plane, covariance[k], scratch, width, rows, radius)

                val meanJ = meanI[COVARIANCE_PAIRS[k][0]]
                val meanL = meanI[COVARIANCE_PAIRS[k][1]]
                val target = covariance[k]
                for (i in 0 until count) {
                    target[i] = (target[i].toDouble() - meanJ[i].toDouble() * meanL[i].toDouble()).toFloat()
                }
            }

            solveCoefficients(meanI, covariance, count, eps)

            for (k in covariance.indices) {
                BoxFilter.mean(covariance[k], covariance[k], scratch, width, rows, radius)
            }
            for (c in meanI.indices) {
                BoxFilter.mean(meanI[c], meanI[c], scratch, width, rows, radius)
            }

            val aRR = covariance[0]; val aRG = covariance[1]; val aRB = covariance[2]
            val aGG = covariance[3]; val aGB = covariance[4]; val aBB = covariance[5]
            val bR = meanI[0]; val bG = meanI[1]; val bB = meanI[2]

            for (y in y0 until y1) {
                val srcRow = y * width
                val planeRow = (y - s0) * width
                for (x in 0 until width) {
                    val i = srcRow + x
                    val p = planeRow + x
                    val pixel = src[i]
                    val r = ((pixel shr 16) and 0xFF).toFloat()
                    val g = ((pixel shr 8) and 0xFF).toFloat()
                    val b = (pixel and 0xFF).toFloat()

                    val qr = aRR[p] * r + aRG[p] * g + aRB[p] * b + bR[p]
                    val qg = aRG[p] * r + aGG[p] * g + aGB[p] * b + bG[p]
                    val qb = aRB[p] * r + aGB[p] * g + aBB[p] * b + bB[p]

                    out[i] = (pixel and ALPHA_MASK) or
                        (round(qr) shl 16) or
                        (round(qg) shl 8) or
                        round(qb)
                }
            }
        }

        return out
    }

    /**
     * Replaces `mean_I` with `b` and `Sigma` with `A`, in place, over the first [count] entries.
     *
     * Both must be produced in one sweep: `b` needs `M` and the original `mean_I`, while `A`
     * overwrites the buffers holding them. The inverse runs in [Double] because the cofactor
     * products reach ~2.6e8 and cancel, a range in which [Float] would lose the small determinants
     * that matter here. Clamping the diagonal keeps `Sigma + eps * U` positive definite
     * numerically, not just algebraically, so it is never singular for `eps > 0`.
     */
    private fun solveCoefficients(
        meanI: Array<FloatArray>,
        covariance: Array<FloatArray>,
        count: Int,
        eps: Double,
    ) {
        val meanR = meanI[0]; val meanG = meanI[1]; val meanB = meanI[2]
        val covRR = covariance[0]; val covRG = covariance[1]; val covRB = covariance[2]
        val covGG = covariance[3]; val covGB = covariance[4]; val covBB = covariance[5]

        for (i in 0 until count) {
            val a = max(covRR[i].toDouble(), 0.0) + eps
            val b = covRG[i].toDouble()
            val c = covRB[i].toDouble()
            val d = max(covGG[i].toDouble(), 0.0) + eps
            val e = covGB[i].toDouble()
            val f = max(covBB[i].toDouble(), 0.0) + eps

            val m0 = d * f - e * e
            val m1 = c * e - b * f
            val m2 = b * e - c * d
            val m3 = a * f - c * c
            val m4 = b * c - a * e
            val m5 = a * d - b * b

            val invDet = 1.0 / (a * m0 + b * m1 + c * m2)
            val mRR = m0 * invDet; val mRG = m1 * invDet; val mRB = m2 * invDet
            val mGG = m3 * invDet; val mGB = m4 * invDet; val mBB = m5 * invDet

            val mr = meanR[i].toDouble()
            val mg = meanG[i].toDouble()
            val mb = meanB[i].toDouble()

            meanR[i] = (eps * (mRR * mr + mRG * mg + mRB * mb)).toFloat()
            meanG[i] = (eps * (mRG * mr + mGG * mg + mGB * mb)).toFloat()
            meanB[i] = (eps * (mRB * mr + mGB * mg + mBB * mb)).toFloat()

            covRR[i] = (1.0 - eps * mRR).toFloat()
            covRG[i] = (-eps * mRG).toFloat()
            covRB[i] = (-eps * mRB).toFloat()
            covGG[i] = (1.0 - eps * mGG).toFloat()
            covGB[i] = (-eps * mGB).toFloat()
            covBB[i] = (1.0 - eps * mBB).toFloat()
        }
    }

    /**
     * @return [value] rounded to nearest and clamped to `[0, 255]`. The clamp is load-bearing:
     *   `mean_a * I + mean_b` is not a pointwise convex combination and can overshoot at edges.
     */
    private fun round(value: Float): Int = (value + 0.5f).toInt().coerceIn(0, 255)

    /** @throws IllegalArgumentException if any argument is out of range for the filter. */
    private fun validate(src: IntArray, width: Int, height: Int, radius: Int, eps: Double) {
        require(width > 0 && height > 0) { "Image must be non-empty, got ${width}x$height" }
        require(src.size == width * height) {
            "Pixel array of ${src.size} does not match ${width}x$height"
        }
        require(radius >= 1) { "radius must be >= 1, got $radius" }
        require(eps > 0.0) { "eps must be > 0, got $eps" }
    }

    /** @return rows a band buffer must hold: the band plus a `2 * radius` halo on each side. */
    private fun bandCapacityRows(band: Int, height: Int, radius: Int): Int =
        min(height, band + 4 * radius)

    /** @return [requested] if positive, otherwise the tallest band fitting [TARGET_WORKING_BYTES]. */
    private fun resolveBandHeight(
        requested: Int,
        width: Int,
        height: Int,
        radius: Int,
        planes: Int,
    ): Int {
        if (requested > 0) return min(requested, height)
        val affordableRows = TARGET_WORKING_BYTES / (planes.toLong() * Float.SIZE_BYTES * width)
        val band = affordableRows - 4L * radius
        // Raise to the floor first, then cap: an image shorter than MIN_BAND_HEIGHT is one band.
        return band.coerceAtLeast(MIN_BAND_HEIGHT.toLong()).coerceAtMost(height.toLong()).toInt()
    }

    /**
     * Invokes [action] per band with the output rows `[y0, y1)` and the loaded rows `[s0, s1)`.
     *
     * The halo is exactly `2 * radius`: output rows need `a` and `b` on `[y0 - r, y1 + r)`, which
     * need the guidance on `[y0 - 2r, y1 + 2r)`. Halo rows do end up holding truncated-window
     * values, but no kept row reads them, so banded output equals whole-image output.
     */
    private inline fun forEachBand(
        height: Int,
        radius: Int,
        bandHeight: Int,
        action: (y0: Int, y1: Int, s0: Int, s1: Int) -> Unit,
    ) {
        val halo = 2 * radius
        var y0 = 0
        while (y0 < height) {
            val y1 = min(y0 + bandHeight, height)
            action(y0, y1, max(0, y0 - halo), min(height, y1 + halo))
            y0 = y1
        }
    }
}
