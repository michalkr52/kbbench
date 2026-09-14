package com.kbbench.algorithm.preprocessing

/**
 * Per-CFA-channel vignetting gains sampled on a coarse grid spanning the sensor active array.
 *
 * [gains] is packed row-major as `[row][column][channel]`, matching Android's
 * `LensShadingMap.copyGainFactors`. Channel order is RGGB: red, green-even, green-odd, blue.
 */
class ShadingMap(
    val gains: FloatArray,
    val columns: Int,
    val rows: Int
)

/**
 * Lens shading (vignetting) correction for a normalized Bayer mosaic.
 */
object LensShadingCorrection {

    /**
     * Multiplies each RAW sample by its bilinearly interpolated channel gain, in place.
     *
     * Must run after black level subtraction and before white balance, since the gains scale
     * signal above black. RAW_SENSOR buffers are never shading-corrected by the device, so
     * without this the frame keeps the lens' corner falloff.
     */
    fun applyInPlace(
        raw: FloatArray,
        width: Int,
        height: Int,
        pattern: CfaPattern,
        map: ShadingMap
    ) {
        require(map.columns >= 2 && map.rows >= 2) {
            "Shading map must be at least 2x2, got ${map.columns}x${map.rows}"
        }
        require(map.gains.size >= map.columns * map.rows * 4) {
            "Shading map has ${map.gains.size} gains, expected ${map.columns * map.rows * 4}"
        }

        // Green sharing a row with red is "even", the other green is "odd".
        val channelAt = IntArray(4) { i -> pattern.channelAt(i and 1, i shr 1) }

        val xScale = (map.columns - 1).toFloat() / (width - 1).coerceAtLeast(1)
        val colIndex = IntArray(width)
        val colFrac = FloatArray(width)
        for (x in 0 until width) {
            val fx = x * xScale
            val c = fx.toInt().coerceIn(0, map.columns - 2)
            colIndex[x] = c
            colFrac[x] = fx - c
        }

        val yScale = (map.rows - 1).toFloat() / (height - 1).coerceAtLeast(1)
        val stride = map.columns * 4
        for (y in 0 until height) {
            val fy = y * yScale
            val r0 = fy.toInt().coerceIn(0, map.rows - 2)
            val ty = fy - r0
            val rowBase0 = r0 * stride
            val rowBase1 = rowBase0 + stride
            val rowOffset = (y and 1) shl 1
            for (x in 0 until width) {
                val channel = channelAt[rowOffset or (x and 1)]
                val tx = colFrac[x]
                val i00 = rowBase0 + colIndex[x] * 4 + channel
                val i10 = rowBase1 + colIndex[x] * 4 + channel
                val top = map.gains[i00] + (map.gains[i00 + 4] - map.gains[i00]) * tx
                val bottom = map.gains[i10] + (map.gains[i10 + 4] - map.gains[i10]) * tx
                val gain = top + (bottom - top) * ty
                val index = y * width + x
                raw[index] = (raw[index] * gain).coerceIn(0f, 1f)
            }
        }
    }
}
