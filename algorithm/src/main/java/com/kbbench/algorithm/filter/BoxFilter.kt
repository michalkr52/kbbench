package com.kbbench.algorithm.filter

import kotlin.math.max
import kotlin.math.min

/**
 * Mean of a square `(2 * radius + 1)` window, computed as a separable two-pass running sum.
 *
 * Cost is O(1) per pixel regardless of [radius]. The window is clamped to the bounds of the
 * supplied plane and normalized by the number of pixels actually inside it, matching the
 * shrink-window convention used elsewhere in this module and the `N = boxfilter(ones(...), r)`
 * normalization of the reference implementation accompanying He et al. (2010).
 *
 * Clamping keeps the window a Cartesian product, so the horizontal count depends only on `x`
 * and the vertical count only on `y`. Separability is therefore exact, not an approximation.
 *
 * Running sums accumulate in [Double] while planes are stored as [Float]. A [Float] accumulator
 * would drift monotonically (~2 levels RMS across a 4000-pixel row of squared 8-bit values),
 * which shows up as a gradient rather than as noise.
 */
object BoxFilter {

    /**
     * Writes the windowed mean of [src] into [dst].
     *
     * [dst] may alias [src]; [tmp] must not alias either. The horizontal pass writes `src -> tmp`
     * and the vertical pass writes `tmp -> dst`, so nothing reads [src] once it has been consumed.
     * A running sum can never be computed in place: the recurrence subtracts `src[i - radius - 1]`,
     * an element the same pass has already overwritten.
     *
     * @param width Plane width in pixels.
     * @param height Plane height in pixels; only the first `width * height` entries are touched.
     */
    fun mean(
        src: FloatArray,
        dst: FloatArray,
        tmp: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        require(width > 0 && height > 0) { "Plane must be non-empty, got ${width}x$height" }
        require(radius >= 0) { "radius must be >= 0, got $radius" }
        require(tmp !== src && tmp !== dst) { "tmp must not alias src or dst" }

        val n = width * height
        require(src.size >= n && dst.size >= n && tmp.size >= n) {
            "Buffers must hold at least $n entries"
        }

        horizontalSums(src, tmp, width, height, radius)
        verticalMeans(tmp, dst, width, height, radius)
    }

    /** Writes raw (undivided) horizontal window sums, leaving a single division for [verticalMeans]. */
    private fun horizontalSums(src: FloatArray, dst: FloatArray, width: Int, height: Int, radius: Int) {
        for (y in 0 until height) {
            val row = y * width
            var sum = 0.0
            for (x in 0..min(radius, width - 1)) {
                sum += src[row + x]
            }
            dst[row] = sum.toFloat()

            for (x in 1 until width) {
                val entering = x + radius
                if (entering < width) sum += src[row + entering]
                val leaving = x - radius - 1
                if (leaving >= 0) sum -= src[row + leaving]
                dst[row + x] = sum.toFloat()
            }
        }
    }

    /**
     * Consumes the horizontal sums row by row, keeping one column accumulator, and divides by the
     * true window area `countX * countY`.
     */
    private fun verticalMeans(src: FloatArray, dst: FloatArray, width: Int, height: Int, radius: Int) {
        val columnSums = DoubleArray(width)

        for (y in 0..min(radius, height - 1)) {
            val row = y * width
            for (x in 0 until width) columnSums[x] += src[row + x]
        }
        emitRow(columnSums, dst, 0, width, height, radius)

        for (y in 1 until height) {
            val entering = y + radius
            if (entering < height) {
                val row = entering * width
                for (x in 0 until width) columnSums[x] += src[row + x]
            }
            val leaving = y - radius - 1
            if (leaving >= 0) {
                val row = leaving * width
                for (x in 0 until width) columnSums[x] -= src[row + x]
            }
            emitRow(columnSums, dst, y, width, height, radius)
        }
    }

    private fun emitRow(
        columnSums: DoubleArray,
        dst: FloatArray,
        y: Int,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val countY = min(height - 1, y + radius) - max(0, y - radius) + 1
        val row = y * width
        for (x in 0 until width) {
            val countX = min(width - 1, x + radius) - max(0, x - radius) + 1
            dst[row + x] = (columnSums[x] / (countX.toDouble() * countY)).toFloat()
        }
    }
}
