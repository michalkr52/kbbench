package com.kbbench.algorithm

import com.kbbench.algorithm.preprocessing.BayerDemosaic
import com.kbbench.algorithm.preprocessing.CfaPattern
import com.kbbench.algorithm.preprocessing.HighlightMode
import com.kbbench.algorithm.preprocessing.PreprocessingConfig
import com.kbbench.algorithm.preprocessing.TransferCurve
import com.kbbench.algorithm.preprocessing.TransferEncoding
import com.kbbench.algorithm.preprocessing.WhiteBalanceGains
import com.kbbench.algorithm.preprocessing.WhiteBalanceMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreprocessingConfigTest {
    @Test
    fun curvesHaveDefinedEndpointsAndMonotonicInvertibleValues() {
        for (encoding in TransferEncoding.entries) {
            val curve = TransferCurve(encoding)
            assertEquals(0.0, curve.encode(0.0), 1e-12)
            assertEquals(1.0, curve.encode(1.0), 1e-12)
            var previous = -1.0
            for (index in 0..1000) {
                val linear = index / 1000.0
                val encoded = curve.encode(linear)
                assertTrue(encoded >= previous)
                assertEquals(linear, curve.decode(encoded.coerceIn(0.0, 1.0)), 1e-7)
                previous = encoded
            }
            assertEquals(0, curve.encode8(-0.1f))
            assertEquals(255, curve.encode8(1.5f))
        }
    }

    @Test
    fun logStrengthChangesEncodingAndLookupMatchesFormula() {
        val shallow = TransferCurve(TransferEncoding.LOG, 1.0)
        val strong = TransferCurve(TransferEncoding.LOG, 100.0)
        assertTrue(strong.encode(0.18) > shallow.encode(0.18))
        for (index in 0..1000) {
            val linear = index / 1000.0
            val expected = (strong.encode(linear) * 255.0 + 0.5).toInt()
            assertTrue(abs(expected - strong.encode8(linear.toFloat())) <= 1)
        }
    }

    @Test
    fun invalidProfilesAndCurveInputsAreRejected() {
        for (invalid in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { TransferCurve(logStrength = invalid) }
            assertFailsWith<IllegalArgumentException> { PreprocessingConfig(exposureOffsetEv = invalid) }
            assertFailsWith<IllegalArgumentException> { TransferCurve().encode(invalid) }
            assertFailsWith<IllegalArgumentException> { TransferCurve().decode(invalid) }
        }
        assertFailsWith<IllegalArgumentException> { TransferCurve(logStrength = 0.0) }
        assertFailsWith<IllegalArgumentException> { PreprocessingConfig(exposureOffsetEv = 5.0) }
        assertFailsWith<IllegalArgumentException> { WhiteBalanceGains(green = 0f) }
        assertFailsWith<IllegalArgumentException> { WhiteBalanceGains(red = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { TransferCurve().encode8(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { TransferCurve().decode(-0.1) }
    }

    @Test
    fun linearEncodingAndExposureAreAppliedBeforeQuantization() {
        val config = PreprocessingConfig(
            transferCurve = TransferCurve(TransferEncoding.LINEAR),
            exposureOffsetEv = 1.0,
        )
        assertContentEquals(
            IntArray(16) { 0xFF808080.toInt() },
            BayerDemosaic.demosaic(FloatArray(16) { 0.25f }, 4, 4, CfaPattern.RGGB, config = config),
        )
    }

    @Test
    fun whiteBalanceSelectionResolvesRelativeGains() {
        val metadata = WhiteBalanceGains(4f, 2f, 1f)
        assertEquals(WhiteBalanceGains(2f, 1f, 0.5f), PreprocessingConfig().resolveWhiteBalance(metadata))
        assertEquals(WhiteBalanceGains(), PreprocessingConfig().resolveWhiteBalance(null))
        assertEquals(
            WhiteBalanceGains(),
            PreprocessingConfig(whiteBalance = WhiteBalanceMode.IDENTITY).resolveWhiteBalance(metadata),
        )
        val manual = PreprocessingConfig(
            whiteBalance = WhiteBalanceMode.MANUAL,
            manualWhiteBalance = WhiteBalanceGains(1f, 2f, 4f),
        )
        assertEquals(WhiteBalanceGains(0.5f, 1f, 2f), manual.resolveWhiteBalance(metadata))
        assertContentEquals(
            IntArray(16) { 0xFF6389BC.toInt() },
            BayerDemosaic.demosaic(FloatArray(16) { 0.25f }, 4, 4, CfaPattern.RGGB, config = manual),
        )
    }

    @Test
    fun channelClippingDoesNotNeutralizeSensorClippedHighlights() {
        assertContentEquals(
            IntArray(16) { 0xFFFFFFBC.toInt() },
            BayerDemosaic.demosaic(
                FloatArray(16) { 1f }, 4, 4, CfaPattern.RGGB,
                rGain = 2f, bGain = 0.5f,
                config = PreprocessingConfig(highlights = HighlightMode.CLIP_CHANNELS),
            ),
        )
    }
}
