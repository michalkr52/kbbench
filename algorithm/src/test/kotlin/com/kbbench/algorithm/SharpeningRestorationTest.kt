package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.ImageAlgorithm
import com.kbbench.algorithm.base.calculateQualityMetrics
import com.kbbench.algorithm.impl.AdaptiveUnsharpMasking
import com.kbbench.algorithm.impl.LinearUnsharpMasking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Scores the sharpeners against ground truth: degrade a frame by a known amount, sharpen it, and
 * compare with the original. PSNR against the algorithm's own input would only say how far it moved
 * the image, and would always rank the more aggressive filter lower.
 *
 * Approximates the protocol of Polesel et al. Section III.B; Section III.A of that paper declines to
 * score enhancement quantitatively at all, since no ideal reference exists.
 *
 * Uses [loadCleanReferences] when a clean set is installed, otherwise falls back to `input.png` —
 * a weaker truth, being a real photo whose sensor noise a restoration score rewards reproducing.
 *
 * The sweep exists because the ranking inverts with the degradation: against blur alone linear UM
 * wins, and once noise is added the adaptive filter wins by a wide margin. Note that under noise
 * neither sharpener beats the do-nothing baseline — PSNR punishes sharpening as such, so the claim
 * the assertions encode is that the adaptive filter does less damage, not that it restores better.
 */
class SharpeningRestorationTest {

    @Test
    fun restorationRankingDependsOnWhetherTheDegradationIsNoisy() {
        val frames = loadCleanReferences().ifEmpty { listOf(fallbackFrame()) }

        val sharpeners: List<Pair<String, ImageAlgorithm>> = listOf(
            "LinearUnsharpMask" to LinearUnsharpMasking(),
            "AdaptiveUnsharpMask" to AdaptiveUnsharpMasking(),
        )

        val report = StringBuilder()
        report.appendLine("Sharpening restoration: degrade the frame, sharpen it, score against the original")
        report.appendLine("PSNR/SSIM are against the truth, so here higher really is better.")
        report.appendLine("frames (${frames.size}): ${frames.joinToString { "${it.name} ${it.width}x${it.height}" }}")
        report.appendLine("values below are means over those frames")

        val deltas = mutableMapOf<Pair<Double, String>, Double>()

        for (sigma in doubleArrayOf(0.0, NOISE_SIGMA)) {
            for (radius in intArrayOf(1, 2, 3)) {
                var baselinePsnr = 0.0
                var baselineSsim = 0.0
                val restoredPsnr = mutableMapOf<String, Double>()
                val restoredSsim = mutableMapOf<String, Double>()
                val totalMs = mutableMapOf<String, Long>()

                for (frame in frames) {
                    val degraded = degrade(frame, radius, sigma)
                    val baseline = calculateQualityMetrics(frame.pixels, degraded)
                    baselinePsnr += baseline.psnr / frames.size
                    baselineSsim += baseline.ssim / frames.size

                    for ((name, algorithm) in sharpeners) {
                        val output = algorithm.process(inputOf(degraded, frame.width, frame.height))
                        val restored = calculateQualityMetrics(frame.pixels, output.pixels)
                        restoredPsnr[name] = (restoredPsnr[name] ?: 0.0) + restored.psnr / frames.size
                        restoredSsim[name] = (restoredSsim[name] ?: 0.0) + restored.ssim / frames.size
                        totalMs[name] = (totalMs[name] ?: 0L) + output.totalTime

                        if (radius == HEADLINE_RADIUS && frame === frames.first()) {
                            writePng(
                                output.pixels, frame.width, frame.height,
                                File("build/test-output/restored_${slug(name)}_sigma${sigma.toInt()}.png"),
                            )
                        }
                    }

                    if (radius == HEADLINE_RADIUS && frame === frames.first()) {
                        writePng(
                            degraded, frame.width, frame.height,
                            File("build/test-output/restored_degraded_sigma${sigma.toInt()}.png"),
                        )
                    }
                }

                report.appendLine()
                report.appendLine("box blur radius $radius, noise sigma $sigma")
                report.appendLine(
                    "  ${"do nothing".padEnd(20)} psnr=${"%7.3f".format(baselinePsnr)} dB  " +
                        "ssim=${"%.5f".format(baselineSsim)}"
                )
                for ((name, _) in sharpeners) {
                    val delta = restoredPsnr.getValue(name) - baselinePsnr
                    if (radius == HEADLINE_RADIUS) deltas[sigma to name] = delta
                    report.appendLine(
                        "  ${name.padEnd(20)} psnr=${"%7.3f".format(restoredPsnr.getValue(name))} dB  " +
                            "ssim=${"%.5f".format(restoredSsim.getValue(name))}  " +
                            "psnr ${"%+.3f".format(delta)} dB vs do nothing  ${totalMs.getValue(name)}ms total"
                    )
                }
            }
        }

        val reportFile = File("build/test-output/sharpening_restoration.txt")
        reportFile.parentFile.mkdirs()
        reportFile.writeText(report.toString())
        println(report)

        // Against blur alone the fixed gain genuinely recovers detail.
        val noiseFreeLinear = deltas.getValue(0.0 to "LinearUnsharpMask")
        assertTrue(
            noiseFreeLinear > 0.0,
            "linear UM failed to recover a noise-free blur: ${"%+.3f".format(noiseFreeLinear)} dB",
        )

        // Once the degradation carries noise the picture inverts, and by a wide margin.
        val noisyLinear = deltas.getValue(NOISE_SIGMA to "LinearUnsharpMask")
        val noisyAdaptive = deltas.getValue(NOISE_SIGMA to "AdaptiveUnsharpMask")
        assertTrue(
            noisyAdaptive > noisyLinear + MARGIN_DB,
            "adaptive UM was expected to survive noise far better than linear: " +
                "${"%+.3f".format(noisyAdaptive)} dB vs ${"%+.3f".format(noisyLinear)} dB",
        )
    }

    private fun degrade(frame: TestFrame, radius: Int, sigma: Double): IntArray {
        val blurred = boxBlurred(frame.pixels, frame.width, frame.height, radius)
        return if (sigma > 0.0) withGaussianNoise(blurred, sigma, seed = 3) else blurred
    }

    private fun fallbackFrame(): TestFrame {
        val (file, image) = loadTestImage()
        return TestFrame(file.nameWithoutExtension, readPixels(image), image.width, image.height)
    }

    private fun inputOf(pixels: IntArray, width: Int, height: Int) = AlgorithmInput(
        frames = listOf(pixels),
        width = width,
        height = height,
        exposureTimes = listOf(10_000_000L),
        isoValues = listOf(100),
        captureTimeMs = 0L,
    )

    private fun slug(name: String): String = if (name.startsWith("Linear")) "linear_um" else "aum"

    private companion object {
        const val NOISE_SIGMA = 4.0

        /** The operating point the assertions read, and the only one whose images get written. */
        const val HEADLINE_RADIUS = 2

        /** Observed gap at the headline point is over 4 dB; this leaves ample room. */
        const val MARGIN_DB = 2.0
    }
}
