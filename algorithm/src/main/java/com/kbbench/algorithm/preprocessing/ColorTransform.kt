package com.kbbench.algorithm.preprocessing

/**
 * Colour-space helpers applied per pixel inside the demosaic loop.
 *
 * These stay float-domain and must not be run as separate 8-bit passes: repeated quantization
 * produces comb-like gaps in the output histogram, and a full-resolution intermediate plane per
 * stage would cost ~48 MiB each at 12.5 MP.
 */
object ColorTransform {

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

    /**
     * Applies the sRGB OETF to a scene-linear value and quantizes it to 8 bits.
     *
     * RAW_SENSOR data is scene-linear; 8-bit output without this encoding crushes shadow detail
     * into a handful of code values and looks severely under-exposed next to a device-rendered
     * preview (e.g. DngCreator/gallery).
     */
    fun encodeSrgb8(linear: Float): Int =
        srgbLut[(linear.coerceIn(0f, 1f) * (SRGB_LUT_SIZE - 1)).toInt()]

    /** Scales each row of a 3x3 transform to sum to 1 so neutral camera RGB maps to neutral sRGB. */
    fun normalizeRows(matrix: FloatArray): FloatArray {
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
}
