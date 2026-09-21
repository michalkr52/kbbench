package com.kbbench.algorithm.base

/**
 * One image, held as three planar channel planes.
 *
 * Capture inputs use unsigned 16-bit planes promoted to one internal scale. Ordered preprocessing
 * stages can instead use finite float planes so signed and above-range intermediate values survive
 * until the stage that intentionally clips or encodes them.
 *
 * Planar rather than packed because every filter works one channel at a time; the packed layout
 * cost each of them an unpacking pass.
 *
 * Alpha is dropped. Camera captures are opaque, the RAW pipeline hardcodes it, and carrying a fourth
 * plane would cost a third more memory for a channel no algorithm reads. A picked PNG with
 * transparency therefore comes out opaque.
 *
 * @property sourceDepth The source precision carried for reporting; it is not the storage depth.
 */
class Frame private constructor(
    private val shortPlanes: Array<ShortArray>?,
    private val floatPlanes: Array<FloatArray>?,
    val width: Int,
    val height: Int,
    val sourceDepth: Int,
    val domain: FrameDomain,
) {
    constructor(
        red: ShortArray,
        green: ShortArray,
        blue: ShortArray,
        width: Int,
        height: Int,
        sourceDepth: Int,
        domain: FrameDomain = FrameDomain.SRGB,
    ) : this(arrayOf(red, green, blue), null, width, height, sourceDepth, domain)

    constructor(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        width: Int,
        height: Int,
        sourceDepth: Int,
        domain: FrameDomain,
    ) : this(null, arrayOf(red, green, blue), width, height, sourceDepth, domain)

    init {
        require(width > 0 && height > 0) { "Frame must be non-empty, got ${width}x$height" }
        require(sourceDepth in 1..INTERNAL_DEPTH) {
            "sourceDepth must be in 1..$INTERNAL_DEPTH, got $sourceDepth"
        }
        val expected = width * height
        require((shortPlanes == null) != (floatPlanes == null)) { "Frame must have one storage mode" }
        val planeSizes = shortPlanes?.map { it.size } ?: floatPlanes!!.map { it.size }
        require(planeSizes.all { it == expected }) {
            "Planes must hold $expected entries, got $planeSizes"
        }
        if (floatPlanes != null) {
            require(floatPlanes.all { plane -> plane.all(Float::isFinite) }) {
                "Unclipped frame planes must contain only finite values"
            }
        }
    }

    val size: Int get() = width * height

    val storage: FrameStorage
        get() = if (shortPlanes != null) FrameStorage.BOUNDED_U16 else FrameStorage.UNCLIPPED_FLOAT

    val isUnclipped: Boolean get() = storage == FrameStorage.UNCLIPPED_FLOAT

    /** The U16 plane, available for bounded frames and retained for existing callers. */
    val red: ShortArray get() = shortPlane(0)
    val green: ShortArray get() = shortPlane(1)
    val blue: ShortArray get() = shortPlane(2)

    /** @return the plane for [channel], numbered 0 red, 1 green, 2 blue. */
    fun plane(channel: Int): ShortArray = shortPlane(channel)

    /** @return the float plane for an unclipped frame. */
    fun floatPlane(channel: Int): FloatArray = when (channel) {
        0 -> floatPlanes?.get(0)
        1 -> floatPlanes?.get(1)
        2 -> floatPlanes?.get(2)
        else -> null
    } ?: throw IllegalStateException("Frame does not contain unclipped float planes")

    /** @return the normalized sample at [index], preserving above-range and negative values. */
    fun sample(channel: Int, index: Int): Float = when (storage) {
        FrameStorage.BOUNDED_U16 -> level(shortPlane(channel), index)
        FrameStorage.UNCLIPPED_FLOAT -> floatPlane(channel)[index]
    }

    /** Stores a normalized sample, clipping only when this frame is U16-backed. */
    fun setSample(channel: Int, index: Int, value: Float) {
        require(value.isFinite()) { "Frame samples must be finite" }
        when (storage) {
            FrameStorage.BOUNDED_U16 -> shortPlane(channel)[index] = store(value)
            FrameStorage.UNCLIPPED_FLOAT -> floatPlane(channel)[index] = value
        }
    }

    /** @return the red channel at [index] in the frame's domain and storage range. */
    fun r(index: Int): Float = sample(0, index)

    fun g(index: Int): Float = sample(1, index)

    fun b(index: Int): Float = sample(2, index)

    /** @return Rec. 601 luma at [index] in the frame's domain and storage range. */
    fun luma(index: Int): Float =
        LUMA_R * r(index) + LUMA_G * g(index) + LUMA_B * b(index)

    /** @return an empty frame preserving geometry, domain and storage mode. */
    fun emptyLike(): Frame = when (storage) {
        FrameStorage.BOUNDED_U16 -> Frame(
            red = ShortArray(size),
            green = ShortArray(size),
            blue = ShortArray(size),
            width = width,
            height = height,
            sourceDepth = sourceDepth,
            domain = domain,
        )
        FrameStorage.UNCLIPPED_FLOAT -> Frame(
            red = FloatArray(size),
            green = FloatArray(size),
            blue = FloatArray(size),
            width = width,
            height = height,
            sourceDepth = sourceDepth,
            domain = domain,
        )
    }

    /** Returns an unclipped copy, or this frame when it already uses float storage. */
    fun toUnclipped(): Frame {
        if (isUnclipped) return this
        return unclipped(
            red = FloatArray(size) { r(it) },
            green = FloatArray(size) { g(it) },
            blue = FloatArray(size) { b(it) },
            width = width,
            height = height,
            sourceDepth = sourceDepth,
            domain = domain,
        )
    }

    /** @return the frame packed as opaque ARGB_8888, clipping normalized samples for display. */
    fun toArgb(): IntArray = IntArray(size) { i ->
        val red8: Int
        val green8: Int
        val blue8: Int
        if (storage == FrameStorage.BOUNDED_U16) {
            red8 = toEightBit(red[i])
            green8 = toEightBit(green[i])
            blue8 = toEightBit(blue[i])
        } else {
            red8 = toEightBit(sample(0, i))
            green8 = toEightBit(sample(1, i))
            blue8 = toEightBit(sample(2, i))
        }
        ALPHA_MASK or
            (red8 shl 16) or
            (green8 shl 8) or
            blue8
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
        fun fromArgb(
            pixels: IntArray,
            width: Int,
            height: Int,
            sourceDepth: Int,
            domain: FrameDomain = FrameDomain.SRGB,
        ): Frame {
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
            return Frame(red, green, blue, width, height, sourceDepth, domain)
        }

        /** Builds a frame whose normalized samples may be signed or above 1.0. */
        fun unclipped(
            red: FloatArray,
            green: FloatArray,
            blue: FloatArray,
            width: Int,
            height: Int,
            sourceDepth: Int,
            domain: FrameDomain,
        ): Frame = Frame(red, green, blue, width, height, sourceDepth, domain)

        /**
         * Builds a frame from separate channel planes of any depth up to [INTERNAL_DEPTH],
         * promoting each sample onto the internal scale.
         *
         * This is the entry point for a sensor that is not 8-bit; [fromArgb] is the special case
         * the Android side happens to need.
         *
         * @param red Row-major samples in `0 until (1 shl sourceDepth)`; out-of-range values are
         *   clamped rather than wrapped.
         * @throws IllegalArgumentException if the planes disagree with the geometry, or
         *   [sourceDepth] is outside `1..`[INTERNAL_DEPTH].
         */
        fun fromChannels(
            red: IntArray,
            green: IntArray,
            blue: IntArray,
            width: Int,
            height: Int,
            sourceDepth: Int,
        ): Frame {
            require(sourceDepth in 1..INTERNAL_DEPTH) {
                "sourceDepth must be in 1..$INTERNAL_DEPTH, got $sourceDepth"
            }
            val count = width * height
            require(red.size == count && green.size == count && blue.size == count) {
                "Planes must hold $count entries, got ${red.size}/${green.size}/${blue.size}"
            }
            val sourceMax = (1 shl sourceDepth) - 1
            fun promote(plane: IntArray) = ShortArray(count) { i ->
                // Exact at both ends: 0 stays 0 and sourceMax lands on MAX_VALUE, with rounding in
                // between. Bit replication only gets that right when sourceDepth divides 16.
                val v = plane[i].coerceIn(0, sourceMax)
                ((v.toLong() * MAX_VALUE + sourceMax / 2) / sourceMax).toInt().toShort()
            }
            return Frame(promote(red), promote(green), promote(blue), width, height, sourceDepth)
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

        private fun toEightBit(sample: Float): Int {
            return (sample.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    private fun shortPlane(channel: Int): ShortArray = when (channel) {
        0, 1, 2 -> shortPlanes?.get(channel)
        else -> null
    } ?: if (channel !in 0..2) {
        throw IllegalArgumentException("channel must be 0..2, got $channel")
    } else {
        throw IllegalStateException("Frame does not contain bounded U16 planes")
    }
}
