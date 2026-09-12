package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.AlgorithmOutput
import com.kbbench.algorithm.impl.AlgorithmRegistry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract every registered algorithm owes its caller, checked generically so an algorithm added
 * later inherits the coverage.
 */
class AlgorithmOutputMetricsTest {

    @Test
    fun outputKeepsTheInputGeometryAndAlpha() {
        forEachAlgorithm { name, input, output ->
            assertEquals(input.width, output.width, "$name changed the width")
            assertEquals(input.height, output.height, "$name changed the height")
            assertEquals(input.width * input.height, output.pixels.size, "$name sized its output wrongly")

            val source = input.frames.first()
            for (i in source.indices) {
                assertEquals(
                    source[i] ushr 24,
                    output.pixels[i] ushr 24,
                    "$name changed alpha at index $i",
                )
            }
        }
    }

    /**
     * `runBenchmarks` hands the same frame arrays to every enabled algorithm in turn, so one that
     * filtered in place would silently corrupt the input of all the algorithms after it — and the
     * corruption would depend on registry order.
     */
    @Test
    fun processDoesNotMutateItsInput() {
        for (algorithm in AlgorithmRegistry().getAll()) {
            val input = fixture()
            val untouched = input.frames.map { it.copyOf() }

            algorithm.process(input)

            for ((index, before) in untouched.withIndex()) {
                assertContentEquals(before, input.frames[index], "${algorithm.name} mutated frame $index")
            }
        }
    }

    /** The interface promises pure, stateless implementations; reruns must therefore agree. */
    @Test
    fun processIsDeterministic() {
        for (algorithm in AlgorithmRegistry().getAll()) {
            val first = algorithm.process(fixture()).pixels
            val second = algorithm.process(fixture()).pixels
            assertContentEquals(first, second, "${algorithm.name} is not deterministic")
        }
    }

    @Test
    fun reportedRuntimeIsNonNegative() {
        forEachAlgorithm { name, _, output ->
            assertTrue(output.totalTime >= 0, "$name reported a negative runtime: ${output.totalTime}")
        }
    }

    private fun forEachAlgorithm(check: (String, AlgorithmInput, AlgorithmOutput) -> Unit) {
        for (algorithm in AlgorithmRegistry().getAll()) {
            val input = fixture()
            check(algorithm.name, input, algorithm.process(input))
        }
    }

    /** Two frames so the exposure-bracketing algorithms are exercised alongside the single-frame ones. */
    private fun fixture(): AlgorithmInput {
        val width = 24
        val height = 18
        val bright = smoothImage(width, height)
        val dark = IntArray(bright.size) { i ->
            val p = bright[i]
            argb(
                p ushr 24,
                ((p shr 16) and 0xFF) / 3,
                ((p shr 8) and 0xFF) / 3,
                (p and 0xFF) / 3,
            )
        }
        return AlgorithmInput(
            frames = listOf(bright, dark),
            width = width,
            height = height,
            exposureTimes = listOf(10_000_000L, 3_300_000L),
            isoValues = listOf(100, 100),
            captureTimeMs = 0L,
        )
    }
}
