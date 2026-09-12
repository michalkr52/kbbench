package com.kbbench.algorithm

import com.kbbench.algorithm.preprocessing.AppliedLensShading
import com.kbbench.algorithm.preprocessing.BayerDemosaic
import com.kbbench.algorithm.preprocessing.CfaPattern
import com.kbbench.algorithm.preprocessing.GainMap
import com.kbbench.algorithm.preprocessing.GainMapCorrection
import com.kbbench.algorithm.preprocessing.LensShadingCorrection
import com.kbbench.algorithm.preprocessing.LensShadingMode
import com.kbbench.algorithm.preprocessing.PreprocessingConfig
import com.kbbench.algorithm.preprocessing.Raw16Decoder
import com.kbbench.algorithm.preprocessing.RawPreprocessor
import com.kbbench.algorithm.preprocessing.ShadingMap
import com.kbbench.algorithm.preprocessing.WhiteBalanceGains
import com.kbbench.algorithm.preprocessing.WhiteBalanceMode
import com.kbbench.algorithm.preprocessing.WhiteBalanceSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RawPreprocessorTest {
    @Test
    fun defaultProcessorMatchesExistingSequenceAcrossPatterns() {
        val gains = WhiteBalanceGains(3f, 2f, 1.5f)
        val matrix = floatArrayOf(1.2f, -0.1f, 0f, -0.1f, 1.1f, 0.1f, 0f, -0.1f, 1.2f)
        for (pattern in CfaPattern.entries) {
            for (map in listOf(null, shadingMap())) {
                val buffer = rawBuffer()
                val raw = Raw16Decoder.normalizeRaw16(buffer, 4, 4, 12, 2, 1023, 64)
                if (map != null) LensShadingCorrection.applyInPlace(raw, 4, 4, pattern, map)
                val expected = BayerDemosaic.demosaic(
                    raw, 4, 4, pattern,
                    rGain = gains.red, gGain = gains.green, bGain = gains.blue, colorMatrix = matrix,
                )
                buffer.position(7)
                val result = RawPreprocessor.process(
                    buffer, 4, 4, 12, 2, 1023, 64, pattern,
                    whiteBalanceGains = gains, colorMatrix = matrix, shadingMap = map,
                )
                assertContentEquals(expected, result.pixels)
                assertEquals(7, buffer.position())
                assertEquals(gains.relativeToGreen(), result.settings.whiteBalanceGains)
                assertEquals(WhiteBalanceSource.METADATA, result.settings.whiteBalanceSource)
            }
        }
    }

    @Test
    fun disablingShadingMatchesProcessingWithoutAMap() {
        val expected = RawPreprocessor.process(rawBuffer(), 4, 4, 12, 2, 1023, 64, CfaPattern.RGGB)
        val disabled = RawPreprocessor.process(
            rawBuffer(), 4, 4, 12, 2, 1023, 64, CfaPattern.RGGB,
            shadingMap = shadingMap(), config = PreprocessingConfig(lensShading = LensShadingMode.OFF),
        )
        assertContentEquals(expected.pixels, disabled.pixels)
        assertEquals(AppliedLensShading.UNAVAILABLE, expected.settings.lensShading)
        assertEquals(AppliedLensShading.OFF, disabled.settings.lensShading)
        assertEquals(WhiteBalanceSource.METADATA_MISSING, expected.settings.whiteBalanceSource)
    }

    @Test
    fun dngGridReconstructionMatchesSensorMap() {
        for (pattern in CfaPattern.entries) {
            val maps = (0..3).map { position ->
                gainMap(position shr 1, position and 1, 2, 2, 1.1f + position * 0.2f)
            }
            val grid = GainMapCorrection.toShadingMap(maps, pattern)!!
            val sensor = RawPreprocessor.process(
                rawBuffer(), 4, 4, 12, 2, 1023, 64, pattern, shadingMap = grid,
            )
            val imported = RawPreprocessor.process(
                rawBuffer(), 4, 4, 12, 2, 1023, 64, pattern, gainMaps = maps,
            )
            assertContentEquals(sensor.pixels, imported.pixels)
            assertEquals(AppliedLensShading.DNG_RECONSTRUCTED_MAP, imported.settings.lensShading)
        }
    }

    @Test
    fun genericDngGainMapKeepsExistingCorrection() {
        val maps = listOf(gainMap(0, 0, 1, 1, 1.5f))
        val raw = Raw16Decoder.normalizeRaw16(rawBuffer(), 4, 4, 12, 2, 1023, 64)
        GainMapCorrection.applyInPlace(raw, 4, 4, maps)
        val expected = BayerDemosaic.demosaic(raw, 4, 4, CfaPattern.RGGB)
        val result = RawPreprocessor.process(
            rawBuffer(), 4, 4, 12, 2, 1023, 64, CfaPattern.RGGB, gainMaps = maps,
        )
        assertContentEquals(expected, result.pixels)
        assertEquals(AppliedLensShading.DNG_GAIN_MAPS, result.settings.lensShading)
    }

    @Test
    fun unsupportedGainMapsAreNotReportedAsApplied() {
        val invalid = gainMap(0, 0, 0, 0, 1f)
        val valid = gainMap(0, 0, 1, 1, 1.5f)
        val unavailable = RawPreprocessor.process(
            rawBuffer(), 4, 4, 12, 2, 1023, 64, CfaPattern.RGGB, gainMaps = listOf(invalid),
        )
        assertEquals(AppliedLensShading.UNAVAILABLE, unavailable.settings.lensShading)
        assertEquals(1, unavailable.settings.suppliedGainMapCount)
        assertEquals(0, unavailable.settings.appliedGainMapCount)
        val partial = RawPreprocessor.process(
            rawBuffer(), 4, 4, 12, 2, 1023, 64, CfaPattern.RGGB, gainMaps = listOf(valid, invalid),
        )
        assertEquals(AppliedLensShading.DNG_GAIN_MAPS, partial.settings.lensShading)
        assertEquals(2, partial.settings.suppliedGainMapCount)
        assertEquals(1, partial.settings.appliedGainMapCount)
    }

    @Test
    fun manualGainsAreReportedAsApplied() {
        val config = PreprocessingConfig(
            whiteBalance = WhiteBalanceMode.MANUAL,
            manualWhiteBalance = WhiteBalanceGains(4f, 2f, 1f),
        )
        val result = RawPreprocessor.process(
            rawBuffer(), 4, 4, 12, 2, 1023, 64, CfaPattern.RGGB, config = config,
        )
        assertEquals(config, result.settings.requested)
        assertEquals(WhiteBalanceSource.MANUAL, result.settings.whiteBalanceSource)
        assertEquals(WhiteBalanceGains(2f, 1f, 0.5f), result.settings.whiteBalanceGains)
    }

    @Test
    fun invalidBufferGeometryAndCalibrationAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            RawPreprocessor.process(rawBuffer(), Int.MAX_VALUE, 4, 12, 2, 1023, 64, CfaPattern.RGGB)
        }
        assertFailsWith<IllegalArgumentException> {
            RawPreprocessor.process(rawBuffer(), 4, 4, 6, 2, 1023, 64, CfaPattern.RGGB)
        }
        assertFailsWith<IllegalArgumentException> {
            RawPreprocessor.process(ByteBuffer.allocate(8), 4, 4, 12, 2, 1023, 64, CfaPattern.RGGB)
        }
        assertFailsWith<IllegalArgumentException> {
            RawPreprocessor.process(rawBuffer(), 4, 4, 12, 2, 64, 64, CfaPattern.RGGB)
        }
        assertFailsWith<IllegalArgumentException> {
            RawPreprocessor.process(rawBuffer(), 4, 4, 12, 2, 1023, Int.MAX_VALUE, CfaPattern.RGGB)
        }
        assertFailsWith<IllegalArgumentException> {
            RawPreprocessor.process(
                rawBuffer(), 4, 4, 12, 2, 1023, 64, CfaPattern.RGGB, colorMatrix = FloatArray(8),
            )
        }
    }

    private fun rawBuffer(): ByteBuffer = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                putShort(row * 12 + column * 2, (row * 256 + column * 83).toShort())
            }
        }
    }

    private fun shadingMap() = ShadingMap(FloatArray(16) { 1f + it * 0.05f }, 2, 2)

    private fun gainMap(top: Int, left: Int, rows: Int, columns: Int, gain: Float) = GainMap(
        top = top, left = left, bottom = 4, right = 4, plane = 0, planes = 1,
        rowPitch = 2, colPitch = 2, mapPointsV = rows, mapPointsH = columns,
        mapSpacingV = 1.0, mapSpacingH = 1.0, mapOriginV = 0.0, mapOriginH = 0.0,
        mapPlanes = 1, gains = FloatArray(rows * columns) { gain },
    )
}
