package com.kbbench.algorithm.filter

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fast bilateral filtering by the signal-processing approach of Paris and Durand (ECCV 2006).
 *
 * The brute-force bilateral filter of Tomasi and Manduchi costs O(r^2) per pixel because the range
 * weight `G_sigmaR(|I_p - I_q|)` makes it non-linear. Paris and Durand's observation is that lifting
 * the image into a three-dimensional space of `(x, y, intensity)` turns it into an ordinary *linear*
 * convolution:
 *
 * ```
 * splat:    each pixel deposits (I_p, 1) at grid position (x/ss, y/ss, I_p/sr)
 * blur:     convolve the grid with a separable Gaussian
 * slice:    read the grid back at each pixel's own position, then divide
 * ```
 *
 * Carrying the pair `(w * I, w)` — homogeneous coordinates — is what lets a single linear blur
 * produce both the numerator and the normalizing denominator of the bilateral filter. The division
 * happens once, at the end.
 *
 * Because a Gaussian is a lowpass filter, the grid may be sampled at the very rate the Gaussian
 * would smooth away, so cell size is set to `(sigmaSpatial, sigmaRange)` and the blur reduces to a
 * fixed `sigma = 1` kernel in grid units. That is the whole speedup: cost becomes O(N) plus a grid
 * pass, **independent of sigma**, where the exact filter grows as sigma^2.
 *
 * ### Departures from the paper, and why
 *
 * - The paper filters grayscale. A joint colour range kernel would need a five-dimensional grid
 *   `(x, y, R, G, B)`, which at realistic settings runs to hundreds of megabytes. This uses the
 *   three-dimensional grid keyed on luma while carrying `(w*R, w*G, w*B, w)` in each cell, so one
 *   shared weight drives all three channels — the arrangement Chen, Paris and Durand (SIGGRAPH
 *   2007) use. Colour edges are therefore resolved by their luma contrast; an isoluminant colour
 *   edge is not seen as an edge. Per-channel range kernels would avoid that but reintroduce the
 *   false colours Tomasi and Manduchi warn about, which is the worse trade.
 * - The convolution kernel is the binomial `[1, 4, 6, 4, 1] / 16`, whose variance is exactly one
 *   grid cell, rather than a sampled Gaussian.
 * - Cells beyond the grid contribute nothing to the convolution. No padding is needed to make that
 *   correct: numerator and denominator are convolved with the identical truncated kernel, so the
 *   ratio remains a proper weighted average. Truncation is self-normalizing here in a way it would
 *   not be for a non-homogeneous filter.
 * - Splatting rounds to the nearest cell while slicing interpolates trilinearly. The paper notes
 *   this asymmetry is deliberate and costs little.
 *
 * ### Banding
 *
 * Grids are built per horizontal band. Output rows `[y0, y1)` slice grid rows
 * `floor(y0/ss) .. floor((y1-1)/ss) + 1`, each of which needs splatted rows within
 * [KERNEL_RADIUS] of it, so the band loads exactly the pixel rows that round into that window.
 * Grid indices are derived from *absolute* pixel coordinates, so cell alignment does not shift
 * between bands and banded output equals whole-image output.
 */
object BilateralGrid {

    /** Binomial kernel with variance of exactly one cell, the sampling rate of the grid. */
    private val KERNEL = doubleArrayOf(1.0, 4.0, 6.0, 4.0, 1.0)
    private const val KERNEL_SUM = 16.0
    private const val KERNEL_RADIUS = 2

    private const val TARGET_WORKING_BYTES = 32L * 1024 * 1024
    private const val MIN_BAND_HEIGHT = 8

    /** Grid holds (w*R, w*G, w*B, w) plus one scratch plane for the separable blur. */
    private const val GRID_PLANES = 5

    private const val ALPHA_MASK = 0xFF shl 24
    private const val MAX_LEVEL = 255.0

    /** Below this a cell had no meaningful support and the division would amplify noise. */
    private const val WEIGHT_FLOOR = 1e-6

    /**
     * @param sigmaSpatial Spatial standard deviation in pixels; also the grid's spatial cell size.
     * @param sigmaRange Range standard deviation on the 8-bit intensity scale; also the cell depth.
     * @param bandHeight Rows produced per band; `0` picks a value from [TARGET_WORKING_BYTES].
     */
    fun filter(
        src: IntArray,
        width: Int,
        height: Int,
        sigmaSpatial: Double,
        sigmaRange: Double,
        bandHeight: Int = 0,
    ): IntArray {
        require(width > 0 && height > 0) { "Image must be non-empty, got ${width}x$height" }
        require(src.size == width * height) {
            "Pixel array of ${src.size} does not match ${width}x$height"
        }
        require(sigmaSpatial > 0.0) { "sigmaSpatial must be > 0, got $sigmaSpatial" }
        require(sigmaRange > 0.0) { "sigmaRange must be > 0, got $sigmaRange" }

        val out = IntArray(src.size)

        // Sized from `round`, matching how splat picks a cell: the last pixel of a row can round
        // up past `floor((width - 1) / ss)`, and folding it back would distort the border.
        val gridWidth = ((width - 1) / sigmaSpatial).roundToInt() + 1
        val gridDepth = (MAX_LEVEL / sigmaRange).roundToInt() + 1
        val lastGridRow = ((height - 1) / sigmaSpatial).roundToInt()

        val band = resolveBandHeight(bandHeight, width, height, sigmaSpatial, sigmaRange)
        val maxGridRows = gridRowSpan(band, sigmaSpatial, lastGridRow)
        val capacity = gridWidth * maxGridRows * gridDepth

        val weightedR = FloatArray(capacity)
        val weightedG = FloatArray(capacity)
        val weightedB = FloatArray(capacity)
        val weights = FloatArray(capacity)
        val scratch = FloatArray(capacity)

        var y0 = 0
        while (y0 < height) {
            val y1 = min(y0 + band, height)

            // Grid rows this band must have blurred, and the splatted rows those depend on.
            val blurredLo = floor(y0 / sigmaSpatial).toInt()
            val blurredHi = min(floor((y1 - 1) / sigmaSpatial).toInt() + 1, lastGridRow)
            val gridLo = max(0, blurredLo - KERNEL_RADIUS)
            val gridHi = min(lastGridRow, blurredHi + KERNEL_RADIUS)
            val gridRows = gridHi - gridLo + 1

            // Pixel rows that round into [gridLo, gridHi].
            val loadFrom = max(0, ceil((gridLo - 0.5) * sigmaSpatial).toInt())
            val loadTo = min(height, floor((gridHi + 0.5) * sigmaSpatial).toInt() + 1)

            val used = gridWidth * gridRows * gridDepth
            weightedR.fill(0f, 0, used)
            weightedG.fill(0f, 0, used)
            weightedB.fill(0f, 0, used)
            weights.fill(0f, 0, used)

            splat(
                src, width, loadFrom, loadTo, sigmaSpatial, sigmaRange,
                gridLo, gridRows, gridWidth, gridDepth,
                weightedR, weightedG, weightedB, weights,
            )

            for (plane in arrayOf(weightedR, weightedG, weightedB, weights)) {
                blur(plane, scratch, gridWidth, gridRows, gridDepth)
            }

            slice(
                src, out, width, y0, y1, sigmaSpatial, sigmaRange,
                gridLo, gridRows, gridWidth, gridDepth,
                weightedR, weightedG, weightedB, weights,
            )

            y0 = y1
        }

        return out
    }

    private fun splat(
        src: IntArray,
        width: Int,
        fromRow: Int,
        toRow: Int,
        sigmaSpatial: Double,
        sigmaRange: Double,
        gridLo: Int,
        gridRows: Int,
        gridWidth: Int,
        gridDepth: Int,
        weightedR: FloatArray,
        weightedG: FloatArray,
        weightedB: FloatArray,
        weights: FloatArray,
    ) {
        for (y in fromRow until toRow) {
            val gy = (y / sigmaSpatial).roundToInt() - gridLo
            if (gy < 0 || gy >= gridRows) continue

            val row = y * width
            for (x in 0 until width) {
                val pixel = src[row + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val gx = (x / sigmaSpatial).roundToInt()
                val gz = (luma(r, g, b) / sigmaRange).roundToInt().coerceIn(0, gridDepth - 1)
                val index = ((gy * gridWidth) + gx) * gridDepth + gz

                weightedR[index] += r.toFloat()
                weightedG[index] += g.toFloat()
                weightedB[index] += b.toFloat()
                weights[index] += 1f
            }
        }
    }

    /** Separable [KERNEL] convolution along z, then x, then y. */
    private fun blur(grid: FloatArray, scratch: FloatArray, gridWidth: Int, gridRows: Int, gridDepth: Int) {
        convolve(grid, scratch, gridWidth * gridRows, gridDepth, stride = 1)
        convolve(scratch, grid, gridRows, gridWidth, stride = gridDepth, outer = gridDepth)
        convolve(grid, scratch, 1, gridRows, stride = gridWidth * gridDepth, outer = gridWidth * gridDepth)
        System.arraycopy(scratch, 0, grid, 0, gridWidth * gridRows * gridDepth)
    }

    /**
     * Convolves along one axis. The array is viewed as [groups] blocks, each holding [count]
     * samples spaced [stride] apart, with [outer] independent interleaved lanes per block.
     */
    private fun convolve(
        src: FloatArray,
        dst: FloatArray,
        groups: Int,
        count: Int,
        stride: Int,
        outer: Int = 1,
    ) {
        val blockSize = count * stride
        for (group in 0 until groups) {
            val blockStart = group * blockSize
            for (lane in 0 until outer) {
                val base = blockStart + lane
                for (i in 0 until count) {
                    var sum = 0.0
                    for (k in -KERNEL_RADIUS..KERNEL_RADIUS) {
                        val j = i + k
                        if (j < 0 || j >= count) continue
                        sum += KERNEL[k + KERNEL_RADIUS] * src[base + j * stride]
                    }
                    dst[base + i * stride] = (sum / KERNEL_SUM).toFloat()
                }
            }
        }
    }

    private fun slice(
        src: IntArray,
        out: IntArray,
        width: Int,
        y0: Int,
        y1: Int,
        sigmaSpatial: Double,
        sigmaRange: Double,
        gridLo: Int,
        gridRows: Int,
        gridWidth: Int,
        gridDepth: Int,
        weightedR: FloatArray,
        weightedG: FloatArray,
        weightedB: FloatArray,
        weights: FloatArray,
    ) {
        for (y in y0 until y1) {
            val fy = y / sigmaSpatial - gridLo
            val row = y * width

            for (x in 0 until width) {
                val pixel = src[row + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val fx = x / sigmaSpatial
                val fz = (luma(r, g, b) / sigmaRange).coerceIn(0.0, (gridDepth - 1).toDouble())

                val weight = interpolate(weights, fy, fx, fz, gridRows, gridWidth, gridDepth)
                out[row + x] = if (weight <= WEIGHT_FLOOR) {
                    // No sample landed nearby; the input is the only defensible answer.
                    pixel
                } else {
                    (pixel and ALPHA_MASK) or
                        (round(interpolate(weightedR, fy, fx, fz, gridRows, gridWidth, gridDepth) / weight) shl 16) or
                        (round(interpolate(weightedG, fy, fx, fz, gridRows, gridWidth, gridDepth) / weight) shl 8) or
                        round(interpolate(weightedB, fy, fx, fz, gridRows, gridWidth, gridDepth) / weight)
                }
            }
        }
    }

    private fun interpolate(
        grid: FloatArray,
        fy: Double,
        fx: Double,
        fz: Double,
        gridRows: Int,
        gridWidth: Int,
        gridDepth: Int,
    ): Double {
        val y0 = floor(fy).toInt()
        val x0 = floor(fx).toInt()
        val z0 = floor(fz).toInt()
        val dy = fy - y0
        val dx = fx - x0
        val dz = fz - z0

        var sum = 0.0
        for (oy in 0..1) {
            val y = y0 + oy
            if (y < 0 || y >= gridRows) continue
            val wy = if (oy == 0) 1.0 - dy else dy
            if (wy == 0.0) continue

            for (ox in 0..1) {
                val x = x0 + ox
                if (x < 0 || x >= gridWidth) continue
                val wx = if (ox == 0) 1.0 - dx else dx
                if (wx == 0.0) continue

                val rowBase = ((y * gridWidth) + x) * gridDepth
                for (oz in 0..1) {
                    val z = z0 + oz
                    if (z < 0 || z >= gridDepth) continue
                    val wz = if (oz == 0) 1.0 - dz else dz
                    if (wz == 0.0) continue

                    sum += wy * wx * wz * grid[rowBase + z]
                }
            }
        }
        return sum
    }

    private fun luma(r: Int, g: Int, b: Int): Double = 0.299 * r + 0.587 * g + 0.114 * b

    private fun round(value: Double): Int = (value + 0.5).toInt().coerceIn(0, 255)

    /**
     * Grid rows a band buffer must hold, including the [KERNEL_RADIUS] halo on each side.
     *
     * A band spans at most `floor((band - 1) / ss) + 1` grid rows of its own, plus one for the
     * trilinear neighbour above; `floor(a + d) - floor(a)` can exceed `floor(d)` by one, so a
     * further row is reserved for that. Under-counting here would overflow the buffer, and
     * `bandedOutputMatchesSingleBandOutput` exercises every division that could trip it.
     */
    private fun gridRowSpan(band: Int, sigmaSpatial: Double, lastGridRow: Int): Int {
        val span = floor((band - 1) / sigmaSpatial).toInt() + 3 + 2 * KERNEL_RADIUS
        return min(span, lastGridRow + 1)
    }

    private fun resolveBandHeight(
        requested: Int,
        width: Int,
        height: Int,
        sigmaSpatial: Double,
        sigmaRange: Double,
    ): Int {
        if (requested > 0) return min(requested, height)

        val gridWidth = ((width - 1) / sigmaSpatial).roundToInt() + 1
        val gridDepth = (MAX_LEVEL / sigmaRange).roundToInt() + 1
        val bytesPerGridRow = GRID_PLANES.toLong() * Float.SIZE_BYTES * gridWidth * gridDepth
        val affordableGridRows = TARGET_WORKING_BYTES / bytesPerGridRow - 2L * KERNEL_RADIUS - 3L
        val band = (affordableGridRows * sigmaSpatial).toLong()

        return band.coerceAtLeast(MIN_BAND_HEIGHT.toLong()).coerceAtMost(height.toLong()).toInt()
    }
}
