package com.kbbench.algorithm.preprocessing

import kotlin.math.floor

/**
 * A DNG `OpcodeList` GainMap: a coarse grid of multiplicative gains covering a sub-sampled
 * rectangle of an image.
 *
 * [top]/[left]/[bottom]/[right] bound the affected area in pixels; only samples where
 * `(row - top) % rowPitch == 0` and `(col - left) % colPitch == 0` are touched, which is how a
 * single CFA channel of a Bayer mosaic is addressed. Map coordinates are relative to the image,
 * where 0.0 is the first row/column and 1.0 the last.
 */
class GainMap(
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int,
    val plane: Int,
    val planes: Int,
    val rowPitch: Int,
    val colPitch: Int,
    val mapPointsV: Int,
    val mapPointsH: Int,
    val mapSpacingV: Double,
    val mapSpacingH: Double,
    val mapOriginV: Double,
    val mapOriginH: Double,
    val mapPlanes: Int,
    val gains: FloatArray,
)

/**
 * Applies DNG GainMap opcodes (vignetting/shading correction) to a normalized Bayer mosaic.
 */
object GainMapCorrection {

    /**
     * Rebuilds a [ShadingMap] from four full-frame per-CFA-channel gain maps, or returns null when
     * the opcodes do not have that shape.
     *
     * `DngCreator` serializes Android's `LensShadingMap` as exactly four `2x2`-pitched gain maps
     * sharing one grid, so recovering the grid lets an imported DNG reuse the same
     * [LensShadingCorrection] the capture path runs instead of a separately interpolated
     * approximation.
     */
    fun toShadingMap(maps: List<GainMap>, pattern: CfaPattern): ShadingMap? {
        if (maps.size != 4) return null
        val first = maps.first()
        val columns = first.mapPointsH
        val rows = first.mapPointsV
        if (columns < 2 || rows < 2) return null
        val uniform = maps.all {
            it.mapPointsH == columns && it.mapPointsV == rows &&
                it.mapPlanes == 1 && it.planes == 1 && it.plane == 0 &&
                it.rowPitch == 2 && it.colPitch == 2 &&
                it.mapOriginV == 0.0 && it.mapOriginH == 0.0 &&
                it.gains.size >= columns * rows
        }
        if (!uniform) return null
        // The four maps must address four distinct CFA positions.
        val positions = maps.map { ((it.top and 1) shl 1) or (it.left and 1) }
        if (positions.toSet().size != 4) return null

        val gains = FloatArray(columns * rows * 4)
        for (map in maps) {
            val channel = pattern.channelAt(map.left and 1, map.top and 1)
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    gains[(row * columns + column) * 4 + channel] = map.gains[row * columns + column]
                }
            }
        }
        return ShadingMap(gains, columns, rows)
    }

    /**
     * Multiplies each covered RAW sample by its bilinearly interpolated gain, in place.
     *
     * Must run after black level subtraction and before white balance, matching where the
     * equivalent correction sits in the capture pipeline.
     */
    fun applyInPlace(raw: FloatArray, width: Int, height: Int, maps: List<GainMap>) {
        for (map in maps) {
            if (map.planes != 1 || map.plane != 0 || map.mapPlanes != 1) continue
            if (map.mapPointsH < 1 || map.mapPointsV < 1) continue
            if (map.gains.size < map.mapPointsH * map.mapPointsV) continue

            val rowPitch = map.rowPitch.coerceAtLeast(1)
            val colPitch = map.colPitch.coerceAtLeast(1)
            val top = map.top.coerceIn(0, height)
            val left = map.left.coerceIn(0, width)
            val bottom = map.bottom.coerceIn(top, height)
            val right = map.right.coerceIn(left, width)
            if (top >= bottom || left >= right) continue

            val spacingV = if (map.mapSpacingV > 0.0) map.mapSpacingV else 1.0
            val spacingH = if (map.mapSpacingH > 0.0) map.mapSpacingH else 1.0
            val vDenominator = (height - 1).coerceAtLeast(1).toDouble()
            val hDenominator = (width - 1).coerceAtLeast(1).toDouble()
            val lastColumn = map.mapPointsH - 1
            val lastRow = map.mapPointsV - 1

            val columnCount = (right - left + colPitch - 1) / colPitch
            val columnIndex = IntArray(columnCount)
            val columnFraction = FloatArray(columnCount)
            for (i in 0 until columnCount) {
                val position = ((left + i * colPitch) / hDenominator - map.mapOriginH) / spacingH
                val index = floor(position).toInt().coerceIn(0, (lastColumn - 1).coerceAtLeast(0))
                columnIndex[i] = index
                columnFraction[i] = (position - index).coerceIn(0.0, 1.0).toFloat()
            }

            var row = top
            while (row < bottom) {
                val position = (row / vDenominator - map.mapOriginV) / spacingV
                val rowIndex = floor(position).toInt().coerceIn(0, (lastRow - 1).coerceAtLeast(0))
                val rowFraction = (position - rowIndex).coerceIn(0.0, 1.0).toFloat()
                val base0 = rowIndex * map.mapPointsH
                val base1 = (rowIndex + 1).coerceAtMost(lastRow) * map.mapPointsH
                val rowOffset = row * width
                for (i in 0 until columnCount) {
                    val column = left + i * colPitch
                    if (column >= right) break
                    val c0 = columnIndex[i]
                    val c1 = (c0 + 1).coerceAtMost(lastColumn)
                    val tx = columnFraction[i]
                    val topGain = map.gains[base0 + c0] + (map.gains[base0 + c1] - map.gains[base0 + c0]) * tx
                    val bottomGain = map.gains[base1 + c0] + (map.gains[base1 + c1] - map.gains[base1 + c0]) * tx
                    val gain = topGain + (bottomGain - topGain) * rowFraction
                    val index = rowOffset + column
                    raw[index] = (raw[index] * gain).coerceIn(0f, 1f)
                }
                row += rowPitch
            }
        }
    }
}
