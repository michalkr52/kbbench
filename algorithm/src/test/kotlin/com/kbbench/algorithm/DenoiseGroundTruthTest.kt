package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.ImageAlgorithm
import com.kbbench.algorithm.base.calculateQualityMetrics
import com.kbbench.algorithm.impl.FastBilateralDenoise
import com.kbbench.algorithm.impl.GuidedFilterColorDenoise
import com.kbbench.algorithm.impl.GuidedFilterDenoise
import java.io.File
import org.junit.Assume.assumeFalse
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Scores the denoisers against real noisy/clean capture pairs rather than against a model of noise.
 *
 * `GuidedFilterTest.improvesPsnrAgainstCleanReference` makes the same claim with additive white
 * Gaussian noise, which no sensor produces: real noise is signal-dependent, correlated by the
 * demosaic and unequal across channels, so a filter can flatter itself against the Gaussian model.
 *
 * Results are broken out per scene and grouped by ISO because the mean over a mixed set hides the
 * thing worth knowing — whether a filter's lead holds at every noise level or only at one end.
 * SIDD encodes ISO and lighting in the scene name; see [parseSiddName].
 *
 * All algorithms run at their default parameters, which are *not* a common operating point, so this
 * ranks configurations rather than algorithms. [DenoiseParameterSweepTest] is the fair comparison.
 *
 * Skipped rather than passed when `src/test/resources/pairs/` is empty; see [loadNoisyPairs] for
 * the layout and the resources README for where to obtain the data.
 */
class DenoiseGroundTruthTest {

    @Test
    fun denoisersImprovePsnrAgainstRealCleanCaptures() {
        val pairs = noisyPairs
        assumeFalse(
            "Brak par w src/test/resources/pairs/ — zobacz src/test/resources/README.md",
            pairs.isEmpty(),
        )

        val denoisers: List<Pair<String, ImageAlgorithm>> = listOf(
            "GuidedFilter" to GuidedFilterDenoise(),
            "GuidedFilterColor" to GuidedFilterColorDenoise(),
            "FastBilateral" to FastBilateralDenoise(),
        )

        // scene -> algorithm (or "noisy") -> PSNR against the clean capture
        val psnr = linkedMapOf<String, MutableMap<String, Double>>()
        val totalMs = mutableMapOf<String, Long>()

        val ordered = pairs.sortedBy { parseSiddName(it.name)?.iso ?: Int.MAX_VALUE }
        for (pair in ordered) {
            val row = mutableMapOf("noisy" to calculateQualityMetrics(pair.clean.pixels, pair.noisy.pixels).psnr)

            for ((name, algorithm) in denoisers) {
                val output = algorithm.process(
                    AlgorithmInput(
                        frames = listOf(pair.noisy.pixels),
                        width = pair.noisy.width,
                        height = pair.noisy.height,
                        exposureTimes = listOf(10_000_000L),
                        isoValues = listOf(100),
                        captureTimeMs = 0L,
                    )
                )
                row[name] = calculateQualityMetrics(pair.clean.pixels, output.pixels).psnr
                totalMs[name] = (totalMs[name] ?: 0L) + output.totalTime

                if (pair === ordered.first()) {
                    writePng(
                        output.pixels, pair.noisy.width, pair.noisy.height,
                        File("build/test-output/denoised_${name.lowercase()}_${pair.name}.png"),
                    )
                }
            }
            psnr[pair.name] = row
        }

        val columns = listOf("noisy") + denoisers.map { it.first }
        val report = StringBuilder()
        report.appendLine("Denoising against real noisy/clean capture pairs")
        report.appendLine("PSNR in dB against the clean capture, so higher is better.")
        report.appendLine("Default parameters, which differ in strength between filters; see DenoiseParameterSweepTest.")
        report.appendLine()

        report.appendLine("per scene, sorted by ISO:")
        report.appendLine("  " + "scene".padEnd(32) + "cam  ISO   light  " + columns.joinToString("  ") { it.padStart(10) })
        for ((scene, row) in psnr) {
            val meta = parseSiddName(scene)
            report.appendLine(
                "  " + scene.padEnd(32) +
                    (meta?.camera ?: "?").padEnd(5) +
                    (meta?.iso?.toString() ?: "?").padStart(5) + "  " +
                    (meta?.brightness ?: "?").padEnd(5) + "  " +
                    columns.joinToString("  ") { "%10.3f".format(row.getValue(it)) }
            )
        }

        report.appendLine()
        report.appendLine("by ISO band (mean PSNR, and gain over the noisy capture):")
        for ((label, range) in ISO_BANDS) {
            val band = psnr.filterKeys { scene -> parseSiddName(scene)?.iso in range }
            if (band.isEmpty()) continue
            val noisy = band.values.map { it.getValue("noisy") }.average()
            report.appendLine(
                "  ${label.padEnd(14)} n=${band.size}  noisy=${"%7.3f".format(noisy)}  " +
                    denoisers.joinToString("  ") { (name, _) ->
                        val mean = band.values.map { it.getValue(name) }.average()
                        "$name=${"%7.3f".format(mean)} (${"%+.3f".format(mean - noisy)})"
                    }
            )
        }

        report.appendLine()
        report.appendLine("overall means over ${psnr.size} pairs:")
        val noisyMean = psnr.values.map { it.getValue("noisy") }.average()
        report.appendLine("  ${"do nothing".padEnd(20)} ${"%7.3f".format(noisyMean)} dB")
        for ((name, _) in denoisers) {
            val mean = psnr.values.map { it.getValue(name) }.average()
            report.appendLine(
                "  ${name.padEnd(20)} ${"%7.3f".format(mean)} dB  ${"%+.3f".format(mean - noisyMean)} dB  " +
                    "${totalMs.getValue(name)}ms total"
            )
            assertTrue(mean > noisyMean, "$name did not improve on the noisy capture: $noisyMean -> $mean dB")
        }

        val first = ordered.first()
        writePng(
            first.noisy.pixels, first.noisy.width, first.noisy.height,
            File("build/test-output/denoised_input_${first.name}.png"),
        )
        writePng(
            first.clean.pixels, first.clean.width, first.clean.height,
            File("build/test-output/denoised_truth_${first.name}.png"),
        )

        val reportFile = File("build/test-output/denoise_ground_truth.txt")
        reportFile.parentFile.mkdirs()
        reportFile.writeText(report.toString())
        println(report)
    }

    private companion object {
        val ISO_BANDS = listOf(
            "ISO <= 200" to 0..200,
            "ISO 400-800" to 201..800,
            "ISO >= 1600" to 801..Int.MAX_VALUE,
        )
    }
}
