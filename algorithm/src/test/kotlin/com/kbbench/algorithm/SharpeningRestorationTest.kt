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
        val frames = cleanReferences.ifEmpty { listOf(fallbackFrame()) }

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
            for (blur in BLUR_SIGMAS) {
                var baselinePsnr = 0.0
                var baselineSsim = 0.0
                val restoredPsnr = mutableMapOf<String, Double>()
                val restoredSsim = mutableMapOf<String, Double>()
                val totalMs = mutableMapOf<String, Long>()

                for (frame in frames) {
                    val degraded = degrade(frame, blur, sigma)
                    val baseline = calculateQualityMetrics(frame.pixels, degraded)
                    baselinePsnr += baseline.psnr / frames.size
                    baselineSsim += baseline.ssim / frames.size

                    for ((name, algorithm) in sharpeners) {
                        val output = algorithm.process(inputOf(degraded, frame.width, frame.height))
                        val restored = calculateQualityMetrics(frame.pixels, output.pixels)
                        restoredPsnr[name] = (restoredPsnr[name] ?: 0.0) + restored.psnr / frames.size
                        restoredSsim[name] = (restoredSsim[name] ?: 0.0) + restored.ssim / frames.size
                        totalMs[name] = (totalMs[name] ?: 0L) + output.totalTime

                        if (blur == HEADLINE_BLUR && frame === frames.first()) {
                            writePng(
                                output.pixels, frame.width, frame.height,
                                File("build/test-output/restored_${slug(name)}_sigma${sigma.toInt()}.png"),
                            )
                        }
                    }

                    if (blur == HEADLINE_BLUR && frame === frames.first()) {
                        writePng(
                            degraded, frame.width, frame.height,
                            File("build/test-output/restored_degraded_sigma${sigma.toInt()}.png"),
                        )
                    }
                }

                report.appendLine()
                report.appendLine("gaussian blur sigma $blur, noise sigma $sigma")
                report.appendLine(
                    "  ${"do nothing".padEnd(20)} psnr=${"%7.3f".format(baselinePsnr)} dB  " +
                        "ssim=${"%.5f".format(baselineSsim)}"
                )
                for ((name, _) in sharpeners) {
                    val delta = restoredPsnr.getValue(name) - baselinePsnr
                    if (blur == HEADLINE_BLUR) deltas[sigma to name] = delta
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

        // Once the degradation carries noise the picture inverts. Both deltas are negative there, so
        // the claim is stated as a ratio of damage: an absolute dB margin would be a property of the
        // image set rather than of the algorithms, and the gap does move a lot between sets.
        val linearDamage = -deltas.getValue(NOISE_SIGMA to "LinearUnsharpMask")
        val adaptiveDamage = -deltas.getValue(NOISE_SIGMA to "AdaptiveUnsharpMask")
        assertTrue(
            adaptiveDamage < linearDamage * MAX_DAMAGE_RATIO,
            "adaptive UM was expected to lose far less than linear under noise: " +
                "${"%.3f".format(adaptiveDamage)} dB vs ${"%.3f".format(linearDamage)} dB",
        )
    }

    private fun degrade(frame: TestFrame, blur: Double, sigma: Double): IntArray {
        val blurred = gaussianBlurred(frame.pixels, frame.width, frame.height, blur)
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

        /** Blur widths in pixels, spanning mild to moderate. */
        val BLUR_SIGMAS = doubleArrayOf(0.5, 1.0, 1.5)

        /** The operating point the assertions read, and the only one whose images get written. */
        const val HEADLINE_BLUR = 1.0

        /** Observed damage ratio is 0.09 on Set5 and 0.04 on a noisy phone photo. */
        const val MAX_DAMAGE_RATIO = 0.5
    }
}
