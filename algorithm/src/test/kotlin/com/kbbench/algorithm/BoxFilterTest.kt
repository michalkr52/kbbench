package com.kbbench.algorithm

import com.kbbench.algorithm.filter.BoxFilter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoxFilterTest {

    @Test
    fun constantPlaneIsUnchanged() {
        val width = 17
        val height = 11
        val src = FloatArray(width * height) { 42f }

        val actual = meanOf(src, width, height, radius = 3)

        for (i in actual.indices) {
            assertEquals(42.0, actual[i].toDouble(), 0.0, "drift at index $i")
        }
    }

    @Test
    fun shrinksWindowAtBorders() {
        // 1 2 3
        // 4 5 6
        // 7 8 9   with radius 1, each window is clipped to the plane and divided by its real area.
        val src = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)

        val actual = meanOf(src, width = 3, height = 3, radius = 1)

        val expected = doubleArrayOf(
            3.0, 3.5, 4.0,
            4.5, 5.0, 5.5,
            6.0, 6.5, 7.0,
        )
        assertPlaneEquals(expected, actual)
    }

    @Test
    fun doesNotTransposeNonSquarePlanes() {
        // 1 2 3 4
        // 5 6 7 8   — a width/height mix-up would not survive this.
        val src = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)

        val actual = meanOf(src, width = 4, height = 2, radius = 1)

        val expected = doubleArrayOf(
            3.5, 4.0, 5.0, 5.5,
            3.5, 4.0, 5.0, 5.5,
        )
        assertPlaneEquals(expected, actual)
    }

    @Test
    fun radiusBeyondPlaneGivesGlobalMean() {
        val src = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)

        val actual = meanOf(src, width = 3, height = 3, radius = 50)

        assertPlaneEquals(DoubleArray(9) { 5.0 }, actual)
    }

    @Test
    fun inPlaceResultMatchesSeparateDestination() {
        val width = 23
        val height = 19
        val random = Random(7)
        val src = FloatArray(width * height) { random.nextFloat() * 255f }

        val separate = FloatArray(src.size)
        BoxFilter.mean(src, separate, FloatArray(src.size), width, height, radius = 4)

        val inPlace = src.copyOf()
        BoxFilter.mean(inPlace, inPlace, FloatArray(src.size), width, height, radius = 4)

        assertContentEquals(separate, inPlace, "in-place result diverged from the aliasing-free one")
    }

    @Test
    fun rejectsScratchAliasingEitherOperand() {
        val plane = FloatArray(9)
        val other = FloatArray(9)

        assertFailsWith<IllegalArgumentException> {
            BoxFilter.mean(plane, other, plane, 3, 3, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            BoxFilter.mean(plane, other, other, 3, 3, 1)
        }
    }

    private fun meanOf(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val dst = FloatArray(src.size)
        BoxFilter.mean(src, dst, FloatArray(src.size), width, height, radius)
        return dst
    }

    private fun assertPlaneEquals(expected: DoubleArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i].toDouble(), 1e-5, "mismatch at index $i")
        }
    }
}
