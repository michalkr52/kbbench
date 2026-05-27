package com.kbbench.algorithm.preprocessing

/**
 * Applies per-channel gain (white balance) correction to ARGB_8888 pixel arrays.
 *
 * Typical usage: extract R/G/B gains from camera AWB metadata and apply them
 * to a demosaiced image to neutralize the green color cast inherent in raw Bayer data.
 */
object WhiteBalance {

    /**
     * Applies white balance gains to an ARGB_8888 pixel array in-place.
     *
     * @param pixels ARGB_8888 pixel array (modified in-place).
     * @param rGain Red channel gain (relative to green).
     * @param gGain Green channel gain (typically 1.0).
     * @param bGain Blue channel gain (relative to green).
     */
    fun apply(pixels: IntArray, rGain: Float, gGain: Float, bGain: Float) {
        // Normalize gains so green is 1.0 (avoids overall brightness shift)
        val normR = rGain / gGain
        val normB = bGain / gGain

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p shr 24) and 0xFF
            val r = ((((p shr 16) and 0xFF) * normR).toInt()).coerceIn(0, 255)
            val g = (p shr 8) and 0xFF  // green stays unchanged
            val b = (((p and 0xFF) * normB).toInt()).coerceIn(0, 255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
