package com.kbbench.algorithm.filter

import kotlin.math.max
import kotlin.math.min

/**
 * Guided image filtering (He, Sun, Tang, ECCV 2010) in its self-guided form, where the guidance
 * image equals the filtered image (`I = p`) and the filter acts as an edge-preserving smoother.
 *
 * Two variants are exposed, matching the two formulations in the paper:
 * - [filterGray] applies the scalar filter to R, G and B independently (Algorithm 1).
 * - [filterColor] uses the full RGB image as guidance and solves a 3x3 system per pixel (Eq. 19).
 *
 * Both operate on ARGB_8888 pixels packed into [Int] in row-major order and preserve alpha.
 *
 * ### Banding
 *
 * Images are processed in horizontal bands so that memory stays bounded and independent of
 * resolution. Producing output rows `[y0, y1)` requires `a` and `b` on `[y0 - r, y1 + r)`, which
 * in turn require the guidance on `[y0 - 2r, y1 + 2r)` — a halo of exactly `2 * radius`.
 *
 * [BoxFilter] clamps its window to the plane it is handed rather than to the full image, and that
 * is sufficient: the halo rows do end up holding truncated-window values, but the kept rows never
 * read them. The first stage at row `y0 - r` spans `[y0 - 2r, y0]`, whose lower edge is exactly the
 * top of the loaded band, and the second stage at row `y0` spans `[y0 - r, y0 + r]`, entirely
 * inside the range the first stage computed correctly. Where a band abuts the real image edge the
 * truncation is the intended shrink-window behaviour. Banded output is therefore equivalent to
 * whole-image output.
 *
 * The vertical running sums restart at each band, so their accumulation order differs from a
 * single-band run. The first box filter is nevertheless bit-identical (its inputs are integral and
 * [Double] is exact well beyond the sums involved); the second differs by ~1e-9, far below the
 * output LSB.
 */
object GuidedFilter {

    /** Working-set budget used to pick a band height when the caller does not supply one. */
    private const val TARGET_WORKING_BYTES = 32L * 1024 * 1024
    private const val MIN_BAND_HEIGHT = 16

    private const val PLANES_GRAY = 4
    private const val PLANES_COLOR = 11

    private const val ALPHA_MASK = 0xFF shl 24

    private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)

    /**
     * Symmetric 3x3 covariance laid out as `[rr, rg, rb, gg, gb, bb]`, and the index of each
     * `(channel, channel)` pair within it.
     */
    private val COVARIANCE_PAIRS = arrayOf(
        intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 2),
        intArrayOf(1, 1), intArrayOf(1, 2), intArrayOf(2, 2),
    )

    /**
     * Filters each colour channel independently, guided by itself.
     *
     * Substituting `p := I` into Algorithm 1 gives `mean_p = mean_I` and `cov_Ip = var_I`, hence
     * `a = var / (var + eps)` and `b = mean_I * (1 - a)`. This is an exact rewrite, not an
     * approximation — it is the edge-preserving special case of Section 3.2.
     *
     * @param eps Regularization on the 8-bit intensity scale.
     * @param bandHeight Rows produced per band; `0` picks a value from [TARGET_WORKING_BYTES].
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

                // Fused: variance, then a, then b. Kept in one sweep because all three are
                // index-local and b consumes the mean that a's buffer would otherwise overwrite.
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
     * Filters using the full RGB image as guidance, solving `(Sigma + eps * U) a = cov` per pixel.
     *
     * Two reductions keep this to eleven planes rather than the twenty-odd a literal reading
     * suggests. Because the filter is self-guided, `cov_Ip` for output channel `c` is simply the
     * `c`-th column of `Sigma`. And with `M = (Sigma + eps * U)^-1` the solution collapses to
     * `A = I3 - eps * M` and `b = eps * M * mean_I`, both symmetric and both writable in place over
     * `Sigma` and `mean_I` respectively.
     *
     * @param eps Regularization on the 8-bit intensity scale.
     * @param bandHeight Rows produced per band; `0` picks a value from [TARGET_WORKING_BYTES].
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

        // meanI[c] becomes b[c]; covariance[k] becomes A[k]. Both transitions happen in place.
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
     * Turns `(mean_I, Sigma)` into `(b, A)` in place.
     *
     * `A` needs `M` and `b` needs both `M` and the original `mean_I`, so the two must be produced
     * in a single sweep — computing them in separate passes would read buffers the other pass has
     * already clobbered.
     *
     * The 3x3 inverse runs in [Double]: cofactor products reach ~2.6e8 and cancel against each
     * other, and [Float] would lose exactly the small-determinant regime the filter cares about.
     * `Sigma + eps * U` has every eigenvalue at least `eps`, so with `eps > 0` it is never
     * singular; clamping the diagonal keeps that true numerically as well as algebraically.
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
     * Rounds to nearest and clamps to `[0, 255]`.
     *
     * `mean_a * I + mean_b` is not a pointwise convex combination, so it can overshoot slightly at
     * edges. Rounding rather than truncating matters here: truncation would darken every channel by
     * half a level on average across the whole image.
     */
    private fun round(value: Float): Int = (value + 0.5f).toInt().coerceIn(0, 255)

    private fun validate(src: IntArray, width: Int, height: Int, radius: Int, eps: Double) {
        require(width > 0 && height > 0) { "Image must be non-empty, got ${width}x$height" }
        require(src.size == width * height) {
            "Pixel array of ${src.size} does not match ${width}x$height"
        }
        require(radius >= 1) { "radius must be >= 1, got $radius" }
        require(eps > 0.0) { "eps must be > 0, got $eps" }
    }

    /** Rows a band buffer must hold: the band itself plus a `2 * radius` halo on each side. */
    private fun bandCapacityRows(band: Int, height: Int, radius: Int): Int =
        min(height, band + 4 * radius)

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
