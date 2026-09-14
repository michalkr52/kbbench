package com.kbbench.algorithm

import com.kbbench.algorithm.preprocessing.BayerDemosaic
import com.kbbench.algorithm.preprocessing.CfaPattern
import kotlin.test.Test
import kotlin.test.assertContentEquals

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
}
