package com.kbbench.app.preprocessing

import com.kbbench.algorithm.base.Frame
import com.kbbench.algorithm.base.FrameDomain
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameArtifactTest {
    @Test
    fun roundTripPreservesGeometryDepthAndUnsignedPlanes() {
        val frame = Frame(
            red = shortArrayOf(0, 1, 32767, (-1).toShort(), Short.MIN_VALUE),
            green = shortArrayOf(5, 257, 40000.toShort(), 65534.toShort(), 65535.toShort()),
            blue = shortArrayOf(65535.toShort(), 3, 4096, 12345, 42),
            width = 5,
            height = 1,
            sourceDepth = 12,
        )
        val file = File.createTempFile("kbbench-frame", ".kbframe")
        try {
            FrameArtifact.save(file, frame)
            val restored = FrameArtifact.load(file)
            assertEquals(frame.width, restored.width)
            assertEquals(frame.height, restored.height)
            assertEquals(frame.sourceDepth, restored.sourceDepth)
            assertArrayEquals(frame.red, restored.red)
            assertArrayEquals(frame.green, restored.green)
            assertArrayEquals(frame.blue, restored.blue)
        } finally {
            file.delete()
        }
    }

    @Test
    fun roundTripPreservesUnclippedPlanesAndDomain() {
        val frame = Frame.unclipped(
            red = floatArrayOf(-1.25f, 0.25f, 1.5f),
            green = floatArrayOf(2f, -0.5f, 0.75f),
            blue = floatArrayOf(0f, 4f, -3f),
            width = 3,
            height = 1,
            sourceDepth = 12,
            domain = FrameDomain.LINEAR,
        )
        val file = File.createTempFile("kbbench-unclipped-frame", ".kbframe")
        try {
            FrameArtifact.save(file, frame)
            val restored = FrameArtifact.load(file)
            assertEquals(frame.width, restored.width)
            assertEquals(frame.height, restored.height)
            assertEquals(frame.sourceDepth, restored.sourceDepth)
            assertEquals(frame.domain, restored.domain)
            assertTrue(restored.isUnclipped)
            assertArrayEquals(frame.floatPlane(0), restored.floatPlane(0), 0f)
            assertArrayEquals(frame.floatPlane(1), restored.floatPlane(1), 0f)
            assertArrayEquals(frame.floatPlane(2), restored.floatPlane(2), 0f)
        } finally {
            file.delete()
        }
    }
}
