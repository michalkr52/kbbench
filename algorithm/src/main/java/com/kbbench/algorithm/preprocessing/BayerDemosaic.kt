package com.kbbench.algorithm.preprocessing

/**
 * Bilinear Bayer demosaicing: converts a normalized RAW Bayer mosaic into an ARGB_8888 pixel array.
 */
object BayerDemosaic {

    /** Raw-normalized value (see [com.kbbench.algorithm.preprocessing.Raw16Decoder]) at or above which a channel is considered sensor-clipped. */
    private const val RAW_CLIP_THRESHOLD = 0.999f

    /**
     * Demosaics a RAW image represented as normalized floats (0..1) into ARGB_8888 pixels.
     *
    * Stage order is white balance -> [colorMatrix] -> exposure -> transfer -> 8-bit quantization,
    * with float intermediates until the output is encoded. Applying any of these to
     * already-quantized 8-bit values instead would truncate repeatedly and produce comb-like
     * gaps/spikes per channel in the output histogram.
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
        colorMatrix: FloatArray? = null,
        config: PreprocessingConfig = PreprocessingConfig(),
    ): IntArray {
        val rX: Int; val rY: Int
        val bX: Int; val bY: Int
        when (pattern) {
            CfaPattern.RGGB -> { rX = 0; rY = 0; bX = 1; bY = 1 }
            CfaPattern.GRBG -> { rX = 1; rY = 0; bX = 0; bY = 1 }
            CfaPattern.GBRG -> { rX = 0; rY = 1; bX = 1; bY = 0 }
            CfaPattern.BGGR -> { rX = 1; rY = 1; bX = 0; bY = 0 }
        }
        val gains = config.resolveWhiteBalance(WhiteBalanceGains(rGain, gGain, bGain))
        val normR = gains.red
        val normB = gains.blue
        val m = colorMatrix?.let { ColorTransform.normalizeRows(it) }
        val exposure = config.exposureMultiplier
        val curve = config.transferCurve

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
                if (config.highlights == HighlightMode.NEUTRALIZE_CLIPPED &&
                    maxOf(r, g, b) >= RAW_CLIP_THRESHOLD) {
                    // A raw-clipped channel means its true brightness is unknown, so scaling it
                    // by normR/normB (typically > 1) while G stays unscaled tints blown highlights
                    // magenta instead of white. Render clipped highlights neutral instead.
                    val neutral = maxOf(lr, lg, lb)
                    lr = neutral; lg = neutral; lb = neutral
                }
                if (m != null) {
                    val tr = m[0] * lr + m[1] * lg + m[2] * lb
                    val tg = m[3] * lr + m[4] * lg + m[5] * lb
                    val tb = m[6] * lr + m[7] * lg + m[8] * lb
                    lr = tr; lg = tg; lb = tb
                }

                val ri = curve.encode8(lr * exposure)
                val gi = curve.encode8(lg * exposure)
                val bi = curve.encode8(lb * exposure)
                pixels[y * width + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
        return pixels
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
