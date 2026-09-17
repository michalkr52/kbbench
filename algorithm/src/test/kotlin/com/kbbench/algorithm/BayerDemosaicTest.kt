package com.kbbench.algorithm

import com.kbbench.algorithm.preprocessing.BayerDemosaic
import com.kbbench.algorithm.preprocessing.CfaPattern
import com.kbbench.algorithm.preprocessing.FrameMaterializer
import com.kbbench.algorithm.preprocessing.HighlightMode
import com.kbbench.algorithm.preprocessing.PreprocessingConfig
import com.kbbench.algorithm.preprocessing.TransferCurve
import com.kbbench.algorithm.preprocessing.TransferEncoding
import com.kbbench.algorithm.base.FrameDomain
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BayerDemosaicTest {
    @Test
    fun defaultQuarterIntensityIsSrgbForEveryCfaPattern() {
        for (pattern in CfaPattern.entries) {
            assertContentEquals(
                IntArray(16) { 0xFF898989.toInt() },
                BayerDemosaic.demosaic(FloatArray(16) { 0.25f }, 4, 4, pattern),
            )
        }
    }

    @Test
    fun whiteBalanceAndMatrixAreAppliedBeforeQuantization() {
        assertContentEquals(
            IntArray(16) { 0xFFE18963.toInt() },
            BayerDemosaic.demosaic(
                FloatArray(16) { 0.25f }, 4, 4, CfaPattern.RGGB,
                rGain = 2f, gGain = 1f, bGain = 0.5f,
                colorMatrix = floatArrayOf(2f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            ),
        )
    }

    @Test
    fun sensorClippedHighlightsRemainNeutral() {
        assertContentEquals(
            IntArray(16) { 0xFFFFFFFF.toInt() },
            BayerDemosaic.demosaic(
                FloatArray(16) { 1f }, 4, 4, CfaPattern.RGGB,
                rGain = 2f, gGain = 1f, bGain = 0.5f,
            ),
        )
    }

    @Test
    fun frameReportsTheSelectedTransferDomain() {
        val raw = FloatArray(16) { 0.25f }
        val linear = BayerDemosaic.demosaicFrame(
            raw, 4, 4, CfaPattern.RGGB,
            config = PreprocessingConfig(
                transferCurve = TransferCurve(TransferEncoding.LINEAR),
            ),
        )
        val log = BayerDemosaic.demosaicFrame(
            raw, 4, 4, CfaPattern.RGGB,
            config = PreprocessingConfig(
                transferCurve = TransferCurve(TransferEncoding.LOG),
            ),
        )

        assertEquals(FrameDomain.LINEAR, linear.domain)
        assertEquals(FrameDomain.LOG, log.domain)
    }

    @Test
    fun unclippedBoundaryPreservesLinearValuesBeforeTransferAndClipping() {
        val boundary = BayerDemosaic.demosaicFrame(
            FloatArray(16) { 1f }, 4, 4, CfaPattern.RGGB,
            rGain = 2f, gGain = 1f, bGain = 0.5f,
            config = PreprocessingConfig(
                transferCurve = TransferCurve(TransferEncoding.SRGB),
                highlights = HighlightMode.CLIP_CHANNELS,
            ),
            unclippedBoundary = true,
        )

        assertTrue(boundary.isUnclipped)
        assertEquals(FrameDomain.LINEAR, boundary.domain)
        assertEquals(2f, boundary.r(5))
        assertEquals(1f, boundary.g(5))
        assertEquals(0.5f, boundary.b(5))

        val materialized = FrameMaterializer.materialize(
            boundary,
            TransferCurve(TransferEncoding.SRGB),
        )
        assertEquals(FrameDomain.SRGB, materialized.domain)
        assertEquals(1f, materialized.r(5))
        assertEquals(1f, materialized.g(5))
        assertEquals(0.7353569f, materialized.b(5), 1f / 65535f)
    }
}
