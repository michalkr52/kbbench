package com.kbbench.algorithm.base

/**
 * One image, held as three unsigned 16-bit channel planes.
 *
 * Sources of different depths are promoted to a single internal scale at ingest rather than each
 * frame carrying its own maximum. A per-frame maximum would make this a union type: every consumer
 * would either branch on it or rescale, and the branch for values that only occur on real sensor
 * data would never be exercised by a test. [sourceDepth] is carried for reporting only and nothing
 * in the pipeline reads it.
 *
 * Planar rather than packed because every filter works one channel at a time; the packed layout
 * cost each of them an unpacking pass.
 *
 * Alpha is dropped. Camera captures are opaque, the RAW pipeline hardcodes it, and carrying a fourth
 * plane would cost a third more memory for a channel no algorithm reads. A picked PNG with
 * transparency therefore comes out opaque.
 *
 * @property red Row-major, `width * height` entries, unsigned; read them through [r] or [level].
 */
class Frame(
    val red: ShortArray,
    val green: ShortArray,
    val blue: ShortArray,
    val width: Int,
    val height: Int,
    val sourceDepth: Int,
) {
    init {
        require(width > 0 && height > 0) { "Frame must be non-empty, got ${width}x$height" }
        require(sourceDepth in 1..INTERNAL_DEPTH) {
            "sourceDepth must be in 1..$INTERNAL_DEPTH, got $sourceDepth"
        }
        val expected = width * height
        require(red.size == expected && green.size == expected && blue.size == expected) {
            "Planes must hold $expected entries, got ${red.size}/${green.size}/${blue.size}"
        }
    }

    val size: Int get() = width * height

    /** @return the plane for [channel], numbered 0 red, 1 green, 2 blue. */
    fun plane(channel: Int): ShortArray = when (channel) {
        0 -> red
        1 -> green
        2 -> blue
        else -> throw IllegalArgumentException("channel must be 0..2, got " + channel)
    }

    /** @return the red channel at [index], normalized to `[0, 1]`. */
    fun r(index: Int): Float = level(red, index)

    fun g(index: Int): Float = level(green, index)

    fun b(index: Int): Float = level(blue, index)

    /** @return Rec. 601 luma at [index], normalized to `[0, 1]`. */
    fun luma(index: Int): Float =
        LUMA_R * r(index) + LUMA_G * g(index) + LUMA_B * b(index)

    /** @return an empty frame of the same geometry and reported depth, for an algorithm's output. */
    fun emptyLike(): Frame = Frame(
        red = ShortArray(size),
        green = ShortArray(size),
        blue = ShortArray(size),
        width = width,
        height = height,
        sourceDepth = sourceDepth,
    )

    /** @return the frame packed as opaque ARGB_8888, the format the Android side speaks. */
    fun toArgb(): IntArray = IntArray(size) { i ->
        ALPHA_MASK or
            (toEightBit(red[i]) shl 16) or
            (toEightBit(green[i]) shl 8) or
            toEightBit(blue[i])
    }

    companion object {
        /** Every source is promoted to this depth; see the note on per-frame maxima above. */
        const val INTERNAL_DEPTH = 16
        const val MAX_VALUE = 65535

        private const val ALPHA_MASK = 0xFF shl 24
        private const val INV_MAX_VALUE = 1.0f / MAX_VALUE

        private const val LUMA_R = 0.299f
        private const val LUMA_G = 0.587f
        private const val LUMA_B = 0.114f

        /**
         * @param sourceDepth bits per channel [pixels] actually carries, so a frame that came from
         *   an 8-bit JPEG is distinguishable in a report from one that came from a 12-bit sensor.
         *   Deliberately has no default: the depth is the whole point of this type.
         */
        fun fromArgb(pixels: IntArray, width: Int, height: Int, sourceDepth: Int): Frame {
            require(pixels.size == width * height) {
                "Pixel array of ${pixels.size} does not match ${width}x$height"
            }
            val count = pixels.size
            val red = ShortArray(count)
            val green = ShortArray(count)
            val blue = ShortArray(count)
            for (i in 0 until count) {
                val p = pixels[i]
                red[i] = fromEightBit((p shr 16) and 0xFF)
                green[i] = fromEightBit((p shr 8) and 0xFF)
                blue[i] = fromEightBit(p and 0xFF)
            }
            return Frame(red, green, blue, width, height, sourceDepth)
        }

        /** @return [value] in `[0, 1]` stored at the internal scale, rounded to nearest and clamped. */
        fun store(value: Float): Short =
            (value * MAX_VALUE + 0.5f).toInt().coerceIn(0, MAX_VALUE).toShort()

        /** @return the unsigned sample at [index] of [plane], normalized to `[0, 1]`. */
        fun level(plane: ShortArray, index: Int): Float =
            (plane[index].toInt() and 0xFFFF) * INV_MAX_VALUE

        /**
         * Bit replication, which maps the source maximum exactly onto [MAX_VALUE] and so keeps a
         * fully saturated 8-bit channel fully saturated.
         */
        private fun fromEightBit(value: Int): Short = ((value shl 8) or value).toShort()

        private fun toEightBit(sample: Short): Int {
            val v = sample.toInt() and 0xFFFF
            // Inverse of the replication above: 65535 -> 255, and the midpoint rounds rather than
            // truncating, which a plain `shr 8` would do.
            return (v + 128) / 257
        }
    }
}
