package com.kbbench.algorithm.preprocessing

import java.nio.ByteBuffer

/**
 * Unpacks RAW16 sensor buffers into a normalized scene-linear Bayer mosaic.
 */
object Raw16Decoder {

    /**
     * Normalizes a 16-bit little-endian RAW buffer into floats using white and black levels.
     */
    fun normalizeRaw16(
        rawBuffer: ByteBuffer,
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
}
