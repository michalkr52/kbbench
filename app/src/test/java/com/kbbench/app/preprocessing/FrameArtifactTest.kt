package com.kbbench.app.preprocessing

import com.kbbench.algorithm.base.Frame
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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
}
