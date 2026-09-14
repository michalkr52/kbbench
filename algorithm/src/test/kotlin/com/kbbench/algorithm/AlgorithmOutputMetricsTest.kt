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
    fun outputKeepsTheInputGeometryAndIsOpaque() {
        forEachAlgorithm { name, input, output ->
            assertEquals(input.width, output.width, "$name changed the width")
            assertEquals(input.height, output.height, "$name changed the height")
            assertEquals(input.width * input.height, output.frame.size, "$name sized its output wrongly")

            // Frame carries no alpha, so everything the pipeline emits is opaque by construction.
            for (pixel in output.frame.toArgb()) {
                assertEquals(0xFF, pixel ushr 24, "$name emitted a non-opaque pixel")
            }
        }
    }

    /**
     * `runBenchmarks` hands the same frames to every enabled algorithm in turn, so one that filtered
     * in place would silently corrupt the input of all the algorithms after it -- and the corruption
     * would depend on registry order.
     */
    @Test
    fun processDoesNotMutateItsInput() {
        for (algorithm in AlgorithmRegistry().getAll()) {
            val input = fixture()
            val untouched = input.frames.map { Triple(it.red.copyOf(), it.green.copyOf(), it.blue.copyOf()) }

            algorithm.process(input)

            for ((index, before) in untouched.withIndex()) {
                val frame = input.frames[index]
                assertContentEquals(before.first, frame.red, "${algorithm.name} mutated red of frame $index")
                assertContentEquals(before.second, frame.green, "${algorithm.name} mutated green of frame $index")
                assertContentEquals(before.third, frame.blue, "${algorithm.name} mutated blue of frame $index")
            }
        }
    }

    /** The interface promises pure, stateless implementations; reruns must therefore agree. */
    @Test
    fun processIsDeterministic() {
        for (algorithm in AlgorithmRegistry().getAll()) {
            val first = algorithm.process(fixture()).frame.toArgb()
            val second = algorithm.process(fixture()).frame.toArgb()
            assertContentEquals(first, second, "${algorithm.name} is not deterministic")
        }
    }

    /** The reported depth describes where the data came from and must survive the algorithm. */
    @Test
    fun outputReportsTheSourceDepthItWasGiven() {
        forEachAlgorithm { name, input, output ->
            assertEquals(
                input.frames.first().sourceDepth,
                output.frame.sourceDepth,
                "$name lost the source depth",
            )
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
        return inputOf(width, height, bright, dark)
    }
}
