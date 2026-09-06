package com.kbbench.algorithm.preprocessing

/**
 * Bayer CFA (Color Filter Array) patterns.
 * Values match Android's CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT constants.
 */
enum class CfaPattern(val id: Int) {
    RGGB(0),
    GRBG(1),
    GBRG(2),
    BGGR(3);

    companion object {
        fun fromId(id: Int): CfaPattern = entries.firstOrNull { it.id == id } ?: RGGB
    }
}

/**
 * Bilinear Bayer demosaicing: converts a normalized RAW Bayer mosaic into an ARGB_8888 pixel array.
 */
object BayerDemosaic {

    /**
     * Demosaics a RAW image represented as normalized floats (0..1) into ARGB_8888 pixels.
     *
     * Stage order is white balance -> [colorMatrix] -> sRGB OETF -> single 8-bit quantization, all in
     * the float domain so the result stays injective per input level. Applying any of these to
     * already-quantized 8-bit values instead would truncate repeatedly and produce comb-like
     * gaps/spikes per channel in the output histogram.
     *
     * The linear result is sRGB-encoded (OETF) before quantization: RAW_SENSOR data is scene-linear,
     * and 8-bit output without this encoding crushes shadow detail into a handful of code values and
     * looks severely under-exposed compared to a device-rendered preview (e.g. DngCreator/gallery).
     *
     * @param colorMatrix row-major 3x3 transform from white-balanced camera RGB to linear sRGB.
     *   Rows are renormalized to sum to 1 so a neutral input stays neutral. Without it the camera's
     *   broad, overlapping CFA primaries render visibly desaturated.
     */
    fun demosaic(
        raw: FloatArray,
        width: Int,
        height: Int,
        pattern: CfaPattern,
        rGain: Float = 1f,
        gGain: Float = 1f,
        bGain: Float = 1f,
        colorMatrix: FloatArray? = null
    ): IntArray {
        val rX: Int; val rY: Int
        val bX: Int; val bY: Int
        when (pattern) {
            CfaPattern.RGGB -> { rX = 0; rY = 0; bX = 1; bY = 1 }
            CfaPattern.GRBG -> { rX = 1; rY = 0; bX = 0; bY = 1 }
            CfaPattern.GBRG -> { rX = 0; rY = 1; bX = 1; bY = 0 }
            CfaPattern.BGGR -> { rX = 1; rY = 1; bX = 0; bY = 0 }
        }
        val normR = rGain / gGain
        val normB = bGain / gGain
        val m = colorMatrix?.let { normalizeRows(it) }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val px = x % 2
                val py = y % 2
                val v = raw[y * width + x]

                val r: Float
                val g: Float
                val b: Float

                if (px == rX && py == rY) {
                    r = v
                    g = avgNeighbors4(raw, x, y, width, height)
                    b = avgDiagonal(raw, x, y, width, height)
                } else if (px == bX && py == bY) {
                    b = v
                    g = avgNeighbors4(raw, x, y, width, height)
                    r = avgDiagonal(raw, x, y, width, height)
                } else {
                    g = v
                    if (py == rY) {
                        r = avgHorizontal(raw, x, y, width, height)
                        b = avgVertical(raw, x, y, width, height)
                    } else {
                        r = avgVertical(raw, x, y, width, height)
                        b = avgHorizontal(raw, x, y, width, height)
                    }
                }

                var lr = r * normR
                var lg = g
                var lb = b * normB
                if (m != null) {
                    val tr = m[0] * lr + m[1] * lg + m[2] * lb
                    val tg = m[3] * lr + m[4] * lg + m[5] * lb
                    val tb = m[6] * lr + m[7] * lg + m[8] * lb
                    lr = tr; lg = tg; lb = tb
                }

                val ri = encodeSrgb8(lr)
                val gi = encodeSrgb8(lg)
                val bi = encodeSrgb8(lb)
                pixels[y * width + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
        return pixels
    }

    private const val SRGB_LUT_SIZE = 65536

    /** IEC 61966-2-1 sRGB OETF, tabulated so the per-pixel path avoids three pow() calls. */
    private val srgbLut: IntArray by lazy {
        IntArray(SRGB_LUT_SIZE) { i ->
            val linear = i.toFloat() / (SRGB_LUT_SIZE - 1)
            val encoded =
                if (linear <= 0.0031308f) linear * 12.92f
                else 1.055f * Math.pow(linear.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
            (encoded * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    private fun encodeSrgb8(linear: Float): Int =
        srgbLut[(linear.coerceIn(0f, 1f) * (SRGB_LUT_SIZE - 1)).toInt()]

    /** Scales each row to sum to 1 so the transform maps neutral camera RGB to neutral sRGB. */
    private fun normalizeRows(matrix: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (row in 0 until 3) {
            val base = row * 3
            val sum = matrix[base] + matrix[base + 1] + matrix[base + 2]
            val scale = if (kotlin.math.abs(sum) > 1e-6f) 1f / sum else 1f
            out[base] = matrix[base] * scale
            out[base + 1] = matrix[base + 1] * scale
            out[base + 2] = matrix[base + 2] * scale
        }
        return out
    }


    /**
     * Normalizes a 16-bit RAW buffer into floats using white and black levels.
     */
    fun normalizeRaw16(
        rawBuffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        whiteLevel: Int,
        blackLevel: Int
    ): FloatArray {
        val range = (whiteLevel - blackLevel).coerceAtLeast(1)
        val raw = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = y * rowStride + x * pixelStride
                val lo = rawBuffer.get(offset).toInt() and 0xFF
                val hi = rawBuffer.get(offset + 1).toInt() and 0xFF
                val val16 = (hi shl 8) or lo
                raw[y * width + x] = ((val16 - blackLevel).toFloat() / range).coerceIn(0f, 1f)
            }
        }
        return raw
    }

    private fun avgNeighbors4(raw: FloatArray, x: Int, y: Int, w: Int, h: Int): Float {
        var sum = 0f; var count = 0
        if (x > 0) { sum += raw[y * w + (x - 1)]; count++ }
        if (x < w - 1) { sum += raw[y * w + (x + 1)]; count++ }
        if (y > 0) { sum += raw[(y - 1) * w + x]; count++ }
        if (y < h - 1) { sum += raw[(y + 1) * w + x]; count++ }
        return if (count > 0) sum / count else 0f
    }

    private fun avgDiagonal(raw: FloatArray, x: Int, y: Int, w: Int, h: Int): Float {
        var sum = 0f; var count = 0
        if (x > 0 && y > 0) { sum += raw[(y - 1) * w + (x - 1)]; count++ }
        if (x < w - 1 && y > 0) { sum += raw[(y - 1) * w + (x + 1)]; count++ }
        if (x > 0 && y < h - 1) { sum += raw[(y + 1) * w + (x - 1)]; count++ }
        if (x < w - 1 && y < h - 1) { sum += raw[(y + 1) * w + (x + 1)]; count++ }
        return if (count > 0) sum / count else 0f
    }

    private fun avgHorizontal(raw: FloatArray, x: Int, y: Int, w: Int, h: Int): Float {
        var sum = 0f; var count = 0
        if (x > 0) { sum += raw[y * w + (x - 1)]; count++ }
        if (x < w - 1) { sum += raw[y * w + (x + 1)]; count++ }
        return if (count > 0) sum / count else 0f
    }

    private fun avgVertical(raw: FloatArray, x: Int, y: Int, w: Int, h: Int): Float {
        var sum = 0f; var count = 0
        if (y > 0) { sum += raw[(y - 1) * w + x]; count++ }
        if (y < h - 1) { sum += raw[(y + 1) * w + x]; count++ }
        return if (count > 0) sum / count else 0f
    }
}
