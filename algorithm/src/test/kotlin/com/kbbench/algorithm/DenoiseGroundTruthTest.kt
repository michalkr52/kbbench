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
 * Skipped rather than passed when `src/test/resources/pairs/` is empty; see [loadNoisyPairs] for
 * the layout and the resources README for where to obtain the data.
 */
class DenoiseGroundTruthTest {

    @Test
    fun denoisersImprovePsnrAgainstRealCleanCaptures() {
        val pairs = loadNoisyPairs()
        assumeFalse(
            "Brak par w src/test/resources/pairs/ — zobacz src/test/resources/README.md",
            pairs.isEmpty(),
        )

        val denoisers: List<Pair<String, ImageAlgorithm>> = listOf(
            "GuidedFilter" to GuidedFilterDenoise(),
            "GuidedFilterColor" to GuidedFilterColorDenoise(),
            "FastBilateral" to FastBilateralDenoise(),
        )

        val report = StringBuilder()
        report.appendLine("Denoising against real noisy/clean capture pairs")
        report.appendLine("PSNR/SSIM are against the clean capture, so here higher really is better.")
        report.appendLine("pairs (${pairs.size}): ${pairs.joinToString { "${it.name} ${it.clean.width}x${it.clean.height}" }}")
        report.appendLine("values below are means over those pairs")
        report.appendLine()

        var baselinePsnr = 0.0
        var baselineSsim = 0.0
        for (pair in pairs) {
            val noisy = calculateQualityMetrics(pair.clean.pixels, pair.noisy.pixels)
            baselinePsnr += noisy.psnr / pairs.size
            baselineSsim += noisy.ssim / pairs.size
        }
        report.appendLine(
            "  ${"do nothing".padEnd(20)} psnr=${"%7.3f".format(baselinePsnr)} dB  " +
                "ssim=${"%.5f".format(baselineSsim)}"
        )

        for ((name, algorithm) in denoisers) {
            var psnr = 0.0
            var ssim = 0.0
            var totalMs = 0L

            for (pair in pairs) {
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
                val quality = calculateQualityMetrics(pair.clean.pixels, output.pixels)
                psnr += quality.psnr / pairs.size
                ssim += quality.ssim / pairs.size
                totalMs += output.totalTime

                if (pair === pairs.first()) {
                    writePng(
                        output.pixels, pair.noisy.width, pair.noisy.height,
                        File("build/test-output/denoised_${name.lowercase()}_${pair.name}.png"),
                    )
                }
            }

            report.appendLine(
                "  ${name.padEnd(20)} psnr=${"%7.3f".format(psnr)} dB  ssim=${"%.5f".format(ssim)}  " +
                    "psnr ${"%+.3f".format(psnr - baselinePsnr)} dB vs do nothing  ${totalMs}ms total"
            )

            assertTrue(
                psnr > baselinePsnr,
                "$name did not improve on the noisy capture: $baselinePsnr -> $psnr dB",
            )
        }

        val first = pairs.first()
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
}
