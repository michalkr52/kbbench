package com.kbbench.algorithm

import com.kbbench.algorithm.base.Frame
import com.kbbench.algorithm.base.FrameDomain
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The ingest contract: what a source of a given depth is worth once it is inside the module.
 */
class FrameTest {

    /**
     * Promotion has to pin both ends, or a fully saturated sensor reading stops being fully
     * saturated and a black one stops being black.
     */
    @Test
    fun promotionIsExactAtBothEndsForEverySupportedDepth() {
        for (depth in 1..Frame.INTERNAL_DEPTH) {
            val sourceMax = (1 shl depth) - 1
            val frame = Frame.fromChannels(
                red = intArrayOf(0, sourceMax),
                green = intArrayOf(0, sourceMax),
                blue = intArrayOf(0, sourceMax),
                width = 2,
                height = 1,
                sourceDepth = depth,
            )

            assertEquals(0.0f, frame.r(0), "depth $depth did not map 0 to 0")
            assertEquals(1.0f, frame.r(1), "depth $depth did not map $sourceMax to full scale")
        }
    }

    /**
     * The two entry points must not disagree: for 8-bit input `v * 65535 / 255` and the bit
     * replication `(v shl 8) or v` are both `v * 257`, so the packed and planar paths coincide.
     */
    @Test
    fun eightBitPlanesAgreeWithThePackedEntryPoint() {
        val levels = IntArray(256) { it }
        val packed = IntArray(256) { argb(255, it, 255 - it, (it * 7) % 256) }

        val planar = Frame.fromChannels(
            red = levels,
            green = IntArray(256) { 255 - it },
            blue = IntArray(256) { (it * 7) % 256 },
            width = 256,
            height = 1,
            sourceDepth = 8,
        )
        val fromPacked = Frame.fromArgb(packed, width = 256, height = 1, sourceDepth = 8)

        assertContentEquals(fromPacked.red, planar.red)
        assertContentEquals(fromPacked.green, planar.green)
        assertContentEquals(fromPacked.blue, planar.blue)
    }

    /**
     * The point of the whole exercise. A 12-bit ramp keeps all 4096 of its levels once it is a
     * [Frame], and only loses them at [Frame.toArgb] — so quantization now happens on the way out
     * rather than before any algorithm has seen the data.
     */
    @Test
    fun twelveBitRampSurvivesIngestAndCollapsesOnlyOnExport() {
        val levels = 1 shl 12
        val ramp = IntArray(levels) { it }
        val frame = Frame.fromChannels(ramp, ramp, ramp, width = levels, height = 1, sourceDepth = 12)

        val stored = frame.red.map { it.toInt() and 0xFFFF }.distinct().size
        assertEquals(levels, stored, "ingest lost levels of a 12-bit ramp")

        val exported = frame.toArgb().map { (it shr 16) and 0xFF }.distinct().size
        assertTrue(exported <= 256, "8-bit export somehow produced $exported levels")
        assertTrue(stored > exported * 4, "the 12-bit frame should hold far more than the export can")
    }

    /** An 8-bit source gains nothing from the wider store, and must not silently claim to. */
    @Test
    fun eightBitRampCarriesOnlyItsOwnLevels() {
        val ramp = IntArray(256) { it }
        val frame = Frame.fromChannels(ramp, ramp, ramp, width = 256, height = 1, sourceDepth = 8)

        assertEquals(256, frame.red.map { it.toInt() and 0xFFFF }.distinct().size)
        assertEquals(8, frame.sourceDepth)
    }

    @Test
    fun samplesBeyondTheDeclaredDepthAreClampedNotWrapped() {
        val frame = Frame.fromChannels(
            red = intArrayOf(-5, 9000),
            green = intArrayOf(0, 0),
            blue = intArrayOf(0, 0),
            width = 2,
            height = 1,
            sourceDepth = 12,
        )

        assertEquals(0.0f, frame.r(0))
        assertEquals(1.0f, frame.r(1))
    }

    @Test
    fun rejectsGeometryAndDepthItCannotHonour() {
        val plane = IntArray(4)

        assertFailsWith<IllegalArgumentException> {
            Frame.fromChannels(plane, plane, plane, width = 3, height = 3, sourceDepth = 8)
        }
        assertFailsWith<IllegalArgumentException> {
            Frame.fromChannels(plane, plane, plane, width = 2, height = 2, sourceDepth = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            Frame.fromChannels(plane, plane, plane, width = 2, height = 2, sourceDepth = 17)
        }
    }

    /** Every algorithm reads through the plane accessor, so an out-of-range channel must not fall through. */
    @Test
    fun rejectsAnUnknownChannel() {
        val frame = Frame.fromArgb(IntArray(4), width = 2, height = 2, sourceDepth = 8)

        assertFailsWith<IllegalArgumentException> { frame.plane(3) }
        assertFailsWith<IllegalArgumentException> { frame.plane(-1) }
    }

    @Test
    fun unclippedFramesPreserveDomainAndOutOfRangeSamples() {
        val frame = Frame.unclipped(
            red = floatArrayOf(-0.25f, 0.5f, 1.25f),
            green = floatArrayOf(0f, 0.5f, 1f),
            blue = floatArrayOf(0f, 0.5f, 1f),
            width = 3,
            height = 1,
            sourceDepth = 12,
            domain = FrameDomain.LINEAR,
        )

        assertEquals(FrameDomain.LINEAR, frame.domain)
        assertTrue(frame.isUnclipped)
        assertEquals(-0.25f, frame.r(0))
        assertEquals(1.25f, frame.r(2))
        assertEquals(0x000000, frame.toArgb()[0] and 0xFFFFFF)
        assertEquals(0xFFFFFF, frame.toArgb()[2] and 0xFFFFFF)
    }

    @Test
    fun emptyLikePreservesUnclippedStorageAndDomain() {
        val frame = Frame.unclipped(
            red = FloatArray(2),
            green = FloatArray(2),
            blue = FloatArray(2),
            width = 2,
            height = 1,
            sourceDepth = 16,
            domain = FrameDomain.LOG,
        )

        val output = frame.emptyLike()
        output.setSample(0, 0, -2f)
        output.setSample(1, 1, 3f)

        assertTrue(output.isUnclipped)
        assertEquals(FrameDomain.LOG, output.domain)
        assertEquals(-2f, output.r(0))
        assertEquals(3f, output.g(1))
    }
}
