package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.impl.AlgorithmRegistry
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the exact output of every registered algorithm on a fixed input.
 *
 * The rest of the suite asserts properties with tolerances, which is the right shape for claims like
 * "an edge survives" but cannot catch a constant that drifts by a factor. Rescaling the intensity
 * units of `RIDGE` or `tau1` wrongly, for instance, changes every output pixel while leaving each
 * existing assertion satisfied. These goldens are the tripwire for that class of change.
 *
 * Regenerate deliberately, never casually, and read the diff:
 * ```
 * gradlew.bat :algorithm:test --tests "*GoldenOutputTest*" -Dgolden.regenerate=true
 * ```
 */
class GoldenOutputTest {

    @Test
    fun everyRegisteredAlgorithmMatchesItsGoldenOutput() {
        val input = goldenInput()
        val regenerate = System.getProperty(REGENERATE_PROPERTY) == "true"
        val goldenDir = File(GOLDEN_DIR)
        if (regenerate) goldenDir.mkdirs()

        val problems = mutableListOf<String>()

        for (algorithm in AlgorithmRegistry().getAll()) {
            val output = algorithm.process(input)
            val file = File(goldenDir, "${algorithm.name}.png")

            if (regenerate) {
                writePng(output.frame.toArgb(), output.width, output.height, file)
                continue
            }
            if (!file.exists()) {
                problems += "${algorithm.name}: no golden at ${file.path}; " +
                    "rerun with -D$REGENERATE_PROPERTY=true to create it"
                continue
            }

            val expected = readPixels(ImageIO.read(file) ?: fail("unreadable golden ${file.path}"))
            describeDifference(algorithm.name, expected, output.frame.toArgb())?.let { problems += it }
        }

        if (regenerate) {
            fail("Goldens regenerated. Review the diff, then rerun without -D$REGENERATE_PROPERTY.")
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /** @return a description of how [actual] departs from [expected], or `null` if they match. */
    private fun describeDifference(name: String, expected: IntArray, actual: IntArray): String? {
        if (expected.size != actual.size) {
            return "$name: golden holds ${expected.size} pixels, output has ${actual.size}"
        }
        var differing = 0
        var worst = 0
        var worstIndex = -1
        for (i in expected.indices) {
            if (expected[i] == actual[i]) continue
            differing++
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val delta = abs(((expected[i] shr shift) and 0xFF) - ((actual[i] shr shift) and 0xFF))
                if (delta > worst) {
                    worst = delta
                    worstIndex = i
                }
            }
        }
        if (differing == 0) return null
        return "$name: $differing of ${expected.size} pixels differ, worst channel delta $worst " +
            "at index $worstIndex (expected ${expected[worstIndex].toUInt().toString(16)}, " +
            "got ${actual[worstIndex].toUInt().toString(16)})"
    }

    /**
     * Two frames of a 64x64 scene, the second two stops darker so [AlgorithmInput.frames] also
     * satisfies the exposure-bracketing algorithms.
     *
     * Built inline from a fixed generator rather than from the shared helpers in `TestImages.kt`,
     * because a golden that moves when an unrelated helper is retuned is worse than no golden.
     * The four quadrants exercise the cases the algorithms diverge on: a flat field where a
     * denoiser must do nothing, a ramp that shows quantization banding, a hard edge, and noise.
     */
    private fun goldenInput(): AlgorithmInput {
        val bright = goldenFrame(exposure = 1.0)
        val dark = goldenFrame(exposure = 0.25)
        return AlgorithmInput(
            frames = listOf(frameOf(bright, SIZE, SIZE), frameOf(dark, SIZE, SIZE)),
            exposureTimes = listOf(10_000_000L, 2_500_000L),
            isoValues = listOf(100, 100),
            captureTimeMs = 0L,
        )
    }

    private fun goldenFrame(exposure: Double): IntArray {
        var state = 0x5EEDu
        fun nextByte(): Int {
            // Numerical Recipes LCG, spelled out so the fixtures cannot shift under a stdlib change.
            state = state * 1664525u + 1013904223u
            return ((state shr 16) and 0xFFu).toInt()
        }

        val half = SIZE / 2
        return IntArray(SIZE * SIZE) { i ->
            val x = i % SIZE
            val y = i / SIZE
            val base = when {
                x < half && y < half -> 128
                x >= half && y < half -> 20 + (x - half) * 210 / (half - 1)
                x < half -> if (x < half / 2) 40 else 210
                else -> 96 + nextByte() % 64
            }
            val scaled = (base * exposure).toInt()
            argb(
                255,
                (scaled + 12).coerceIn(0, 255),
                scaled.coerceIn(0, 255),
                (scaled - 12).coerceIn(0, 255),
            )
        }
    }

    private companion object {
        const val SIZE = 64
        const val GOLDEN_DIR = "src/test/resources/golden"
        const val REGENERATE_PROPERTY = "golden.regenerate"
    }
}
