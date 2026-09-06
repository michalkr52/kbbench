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
 * Compares the denoisers at their best rather than at their defaults.
 *
 * Both filters take one knob setting the contrast below which variation counts as noise, and
 * `FastBilateralDenoise` documents its `sigmaRange` as comparable to the square root of the guided
 * filter's `eps`. Sweeping that shared threshold puts them on one axis, so the curves are
 * commensurate and each filter can be read at its own optimum.
 *
 * `sigmaSpatial` is held at its default: for the bilateral grid it is a sampling rate as well as a
 * kernel width, so sweeping it would trade accuracy against speed rather than against smoothing.
 *
 * Skipped rather than passed when `src/test/resources/pairs/` is empty.
 */
class DenoiseParameterSweepTest {

    @Test
    fun eachDenoiserHasAnInteriorOptimumAndIsRankedThere() {
        val pairs = noisyPairs
        assumeFalse(
            "Empty resource dir: src/test/resources/pairs/",
            pairs.isEmpty(),
        )

        val families: List<Pair<String, (Double) -> ImageAlgorithm>> = listOf(
            "GuidedFilter" to { t -> GuidedFilterDenoise(eps = t * t) },
            "GuidedFilterColor" to { t -> GuidedFilterColorDenoise(eps = t * t) },
            "FastBilateral" to { t -> FastBilateralDenoise(sigmaRange = t) },
        )

        val noisyMean = pairs.map { calculateQualityMetrics(it.clean.pixels, it.noisy.pixels).psnr }.average()

        // family -> threshold -> mean PSNR over all pairs
        val curves = linkedMapOf<String, MutableMap<Double, Double>>()
        for ((name, build) in families) {
            val curve = linkedMapOf<Double, Double>()
            for (threshold in THRESHOLDS) {
                val algorithm = build(threshold)
                curve[threshold] = pairs.map { pair ->
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
                    calculateQualityMetrics(pair.clean.pixels, output.pixels).psnr
                }.average()
            }
            curves[name] = curve
        }

        val report = StringBuilder()
        report.appendLine("Denoiser parameter sweep over ${pairs.size} real noisy/clean pairs")
        report.appendLine("Shared threshold t: GuidedFilter eps = t^2, FastBilateral sigmaRange = t.")
        report.appendLine("Mean PSNR in dB against the clean capture; noisy capture scores ${"%.3f".format(noisyMean)}.")
        report.appendLine()
        report.appendLine("  " + "t".padStart(6) + curves.keys.joinToString("") { it.padStart(20) })
        for (threshold in THRESHOLDS) {
            report.appendLine(
                "  " + "%.3f".format(threshold).padStart(6) +
                    curves.values.joinToString("") { "%20.3f".format(it.getValue(threshold)) }
            )
        }

        report.appendLine()
        report.appendLine("best of each family:")
        val best = curves.mapValues { (_, curve) -> curve.maxBy { it.value } }
        for ((name, entry) in best) {
            report.appendLine(
                "  ${name.padEnd(20)} t=${"%.3f".format(entry.key)}  ${"%7.3f".format(entry.value)} dB  " +
                    "${"%+.3f".format(entry.value - noisyMean)} dB vs do nothing"
            )
        }
        val winner = best.maxBy { it.value.value }
        report.appendLine(
            "  winner at own optimum: ${winner.key} by " +
                "${"%.3f".format(winner.value.value - best.filterKeys { it != winner.key }.values.maxOf { it.value })} dB"
        )

        val reportFile = File("build/test-output/denoise_parameter_sweep.txt")
        reportFile.parentFile.mkdirs()
        reportFile.writeText(report.toString())
        println(report)

        for ((name, entry) in best) {
            assertTrue(entry.value > noisyMean, "$name never beat the noisy capture: ${entry.value} dB")

            // An optimum sitting on an endpoint means the sweep did not bracket it, and the
            // "best of each family" comparison below would then be reading a truncated curve.
            assertTrue(
                entry.key != THRESHOLDS.first() && entry.key != THRESHOLDS.last(),
                "$name peaked at the edge of the swept range (t=${entry.key}); widen THRESHOLDS",
            )
        }
    }

    private companion object {
        /** Normalized contrast thresholds, bracketing both filters' defaults (0.04 and 0.1). */
        val THRESHOLDS = listOf(0.02, 0.03, 0.045, 0.065, 0.09, 0.13, 0.18, 0.25)
    }
}
