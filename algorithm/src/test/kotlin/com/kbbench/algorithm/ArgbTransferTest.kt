package com.kbbench.algorithm

import com.kbbench.algorithm.preprocessing.ArgbTransfer
import com.kbbench.algorithm.preprocessing.PreprocessingConfig
import com.kbbench.algorithm.preprocessing.TransferCurve
import com.kbbench.algorithm.preprocessing.TransferEncoding
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ArgbTransferTest {
    @Test
    fun defaultInputAndPreviewPreserveEveryCodeAndAlpha() {
        val pixels = IntArray(256) { code -> (code shl 24) or (code shl 16) or (code shl 8) or code }
        val input = ArgbTransfer.fromSrgb(PreprocessingConfig()).apply(pixels)
        assertContentEquals(pixels, input)
        assertNotSame(pixels, input)
        assertContentEquals(pixels, ArgbTransfer.toSrgb(TransferCurve()).apply(pixels))
    }

    @Test
    fun linearPreviewEncodesChannelsWithoutChangingCanonicalSamples() {
        val canonical = intArrayOf(0x7F4080FF, 0x00000000)
        val original = canonical.copyOf()
        val display = ArgbTransfer.toSrgb(TransferCurve(TransferEncoding.LINEAR)).apply(canonical)
        assertContentEquals(intArrayOf(0x7F89BCFF, 0), display)
        assertContentEquals(original, canonical)
    }

    @Test
    fun renderedInputIsLinearizedBeforeExposureAndReencoding() {
        val config = PreprocessingConfig(
            transferCurve = TransferCurve(TransferEncoding.LINEAR), exposureOffsetEv = 1.0,
        )
        assertEquals(0xFF6E6E6E.toInt(), ArgbTransfer.fromSrgb(config).apply(0xFF808080.toInt()))
    }

    @Test
    fun logPreviewUsesDeclaredStrengthAndFixedSrgbRendering() {
        for (strength in listOf(1.0, 9.0, 100.0)) {
            val curve = TransferCurve(TransferEncoding.LOG, strength)
            val preview = ArgbTransfer.toSrgb(curve)
            val srgb = TransferCurve()
            for (code in 0..255) {
                val expected = (srgb.encode(curve.decode(code / 255.0).coerceIn(0.0, 1.0)) * 255 + 0.5).toInt()
                val actual = preview.apply(code) and 255
                assertTrue(abs(expected - actual) <= 1)
            }
        }
    }
}
