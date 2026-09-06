package com.kbbench.algorithm.filter

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fast approximation of the bilateral filter, Paris and Durand (ECCV 2006): [splat] the image into a
 * `(x, y, intensity)` grid downsampled to `(sigmaSpatial, sigmaRange)`, [blur] it, then [slice] it
 * back. Cells carry homogeneous `(w * I, w)`, so one linear blur yields both the numerator and the
 * normalizing denominator and the division happens once, at the end.
 *
 * Departures from the paper:
 * - The paper filters grayscale. A joint colour range kernel would need a five-dimensional
 *   `(x, y, R, G, B)` grid, hundreds of megabytes at realistic settings, so the grid is keyed on
 *   luma and each cell carries `(w*R, w*G, w*B, w)` — the arrangement of Chen, Paris and Durand
 *   (SIGGRAPH 2007). One shared weight therefore drives all three channels, and an isoluminant
 *   colour edge is not seen as an edge. Per-channel range kernels would see it, but at the cost of
 *   the false colours Tomasi and Manduchi warn about.
 * - The blur kernel is the binomial `[1, 4, 6, 4, 1] / 16`, variance exactly one cell, rather than a
 *   sampled Gaussian.
 * - No padding around the grid. Numerator and denominator are convolved with the same truncated
 *   kernel, so their ratio stays a proper weighted average; truncation is self-normalizing here in a
 *   way it would not be without homogeneous coordinates.
 * - Images are processed in horizontal bands to bound memory. Grid indices come from *absolute*
 *   pixel coordinates so the lattice does not shift between bands; see [filter] for the halo rule.
 */
object BilateralGrid {

    /** Binomial kernel with variance of exactly one cell, the sampling rate of the grid. */
    private val KERNEL = doubleArrayOf(1.0, 4.0, 6.0, 4.0, 1.0)
    private const val KERNEL_SUM = 16.0
    private const val KERNEL_RADIUS = 2

    private const val TARGET_WORKING_BYTES = 32L * 1024 * 1024
    private const val MIN_BAND_HEIGHT = 8

    /**
     * Four homogeneous planes `(w*R, w*G, w*B, w)` plus one scratch plane for the separable blur.
     * Each is indexed `((gy * gridWidth) + gx) * gridDepth + gz`, so z is contiguous, x has stride
     * `gridDepth` and y has stride `gridWidth * gridDepth` — the strides [convolve] is given.
     */
    private const val GRID_PLANES = 5

    private const val ALPHA_MASK = 0xFF shl 24
    private const val MAX_LEVEL = 255.0

    /** Below this a cell had no meaningful support and the division would amplify noise. */
    private const val WEIGHT_FLOOR = 1e-6

    /**
     * Output rows `[y0, y1)` slice grid rows `floor(y0/ss)` through `floor((y1-1)/ss) + 1`, and each
     * of those needs splatted rows within [KERNEL_RADIUS] cells of it, so a band loads exactly the
     * pixel rows rounding into that widened window.
     *
     * @param src ARGB_8888 pixels, row-major, exactly `width * height` entries.
     * @param sigmaSpatial Spatial standard deviation in pixels, strictly positive. Doubles as the
     *   grid's spatial cell size, so raising it makes the filter cheaper rather than costlier.
     * @param sigmaRange Range standard deviation on the 8-bit intensity scale, strictly positive.
     *   Doubles as the grid's cell depth.
     * @param bandHeight Rows produced per band; `0` derives one from [TARGET_WORKING_BYTES].
     * @return a new ARGB_8888 array of the same dimensions, alpha copied from [src].
     * @throws IllegalArgumentException if the dimensions disagree with [src], or a sigma is not
     *   positive.
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

    /**
     * Accumulates pixel rows `[fromRow, toRow)` into the band's grid, which covers absolute grid
     * rows `[gridLo, gridLo + gridRows)`. Rows rounding outside that span are skipped; by
     * construction no sliced row depends on them.
     */
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
     * Convolves [src] into [dst] along one axis, viewing the array as [groups] blocks of [count]
     * samples spaced [stride] apart, with [outer] interleaved lanes sharing each block. Taps falling
     * outside the block are dropped rather than clamped or wrapped.
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

    /**
     * Writes output rows `[y0, y1)` into [out], reading the blurred grid trilinearly at each pixel's
     * own continuous position.
     */
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

    /**
     * @return [grid] sampled trilinearly at `(fy, fx, fz)` in cell units. Corners outside the grid
     *   are dropped, which matches [convolve] treating absent cells as empty.
     */
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
     * @return grid rows a band buffer must hold, including the [KERNEL_RADIUS] halo on each side.
     *   The `+ 3` covers the band's own `floor((band - 1) / ss) + 1` rows, the trilinear neighbour
     *   above, and the fact that `floor(a + d) - floor(a)` can exceed `floor(d)` by one.
     *   Under-counting overflows the grid buffer.
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
