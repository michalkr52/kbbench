package com.kbbench.algorithm.base

/**
 * Conversion between the packed ARGB_8888 contract and the normalized `[0, 1]` domain the filters
 * compute in.
 *
 * Filters work normalized so their tuning constants carry the units their source papers quote and
 * stay independent of how many bits the input actually had. Quantization then happens once, in
 * [quantize], on the way out.
 */
internal object Pixels {

    /** Largest value representable per channel by the current packed contract. */
    const val MAX_LEVEL = 255.0f

    private const val INV_MAX_LEVEL = 1.0f / MAX_LEVEL

    const val ALPHA_MASK = 0xFF shl 24

    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    fun red(pixel: Int): Float = ((pixel shr 16) and 0xFF) * INV_MAX_LEVEL

    fun green(pixel: Int): Float = ((pixel shr 8) and 0xFF) * INV_MAX_LEVEL

    fun blue(pixel: Int): Float = (pixel and 0xFF) * INV_MAX_LEVEL

    /** @return the channel selected by [shift], one of 16, 8 or 0, normalized to `[0, 1]`. */
    fun channel(pixel: Int, shift: Int): Float = ((pixel shr shift) and 0xFF) * INV_MAX_LEVEL

    /** @return Rec. 601 luma of [pixel], normalized to `[0, 1]`. */
    fun luma(pixel: Int): Float =
        LUMA_R * red(pixel) + LUMA_G * green(pixel) + LUMA_B * blue(pixel)

    /**
     * @return [value] mapped from `[0, 1]` onto the packed contract, rounded to nearest and clamped.
     *   Rounding rather than truncating matters: truncation darkens every channel by half a level
     *   on average across the whole image.
     */
    fun quantize(value: Float): Int = (value * MAX_LEVEL + 0.5f).toInt().coerceIn(0, 255)

    /** @return [r], [g] and [b] in `[0, 1]` packed under the alpha of [source]. */
    fun pack(source: Int, r: Float, g: Float, b: Float): Int =
        (source and ALPHA_MASK) or
            (quantize(r) shl 16) or
            (quantize(g) shl 8) or
            quantize(b)
}
