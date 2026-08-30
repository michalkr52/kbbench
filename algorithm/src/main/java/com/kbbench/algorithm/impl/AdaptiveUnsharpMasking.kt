package com.kbbench.algorithm.impl

import com.kbbench.algorithm.base.AlgorithmCategory
import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.AlgorithmMetadata
import com.kbbench.algorithm.base.AlgorithmOutput
import com.kbbench.algorithm.base.FrameRequirements
import com.kbbench.algorithm.base.ImageAlgorithm
import com.kbbench.algorithm.base.InputFrameType
import com.kbbench.algorithm.base.measureMs
import com.kbbench.algorithm.filter.AdaptiveDirectionalUnsharpMask

/**
 * Single-frame adaptive unsharp masking after Polesel, Ramponi and Mathews (IEEE TIP 9(3), 2000).
 *
 * Two directional Laplacians are scaled by gains that a Gauss-Newton recursion adapts per pixel, so
 * that smooth areas are left alone, medium-contrast detail is emphasized most and high-contrast
 * edges only moderately. The whole adaptation runs on luma and the resulting correction is applied
 * to R, G and B alike; alpha is preserved. See [AdaptiveDirectionalUnsharpMask] for the equations.
 *
 * Defaults are the values Table I reports for the paper's own enhancement experiment. They were
 * tuned on a 256x256 crop of Lena, so [tau1] in particular — which the paper ties directly to the
 * input's noise level, quoting the range `[30, 60]` — may need raising for noisier captures.
 *
 * @param tau1 Local-variance threshold below which a pixel counts as smooth.
 * @param tau2 Local-variance threshold at or above which a pixel counts as high-contrast.
 * @param alphaB Desired dynamics multiplier in smooth areas; `1` means no enhancement.
 * @param alphaDl Multiplier in high-contrast areas.
 * @param alphaDh Multiplier in medium-contrast areas; the largest of the three.
 * @param mu Gauss-Newton step size controlling how fast the gains converge.
 * @param beta Forgetting factor of the autocorrelation recursion.
 * @param maxGain Clamp on either directional gain, bounding the transients the paper describes
 *   when the recursion crosses from a detail zone into a smooth one.
 */
class AdaptiveUnsharpMasking(
    private val tau1: Double = 60.0,
    private val tau2: Double = 200.0,
    private val alphaB: Double = 1.0,
    private val alphaDl: Double = 3.0,
    private val alphaDh: Double = 4.0,
    private val mu: Double = 0.1,
    private val beta: Double = 0.5,
    private val maxGain: Double = 4.0,
) : ImageAlgorithm {

    init {
        require(tau1 >= 0.0) { "AdaptiveUnsharpMask requires tau1 >= 0, got $tau1" }
        require(tau1 < tau2) { "AdaptiveUnsharpMask requires tau1 < tau2, got $tau1 and $tau2" }
        require(alphaB >= 1.0) { "AdaptiveUnsharpMask requires alphaB >= 1, got $alphaB" }
        require(alphaDl > 1.0) { "AdaptiveUnsharpMask requires alphaDl > 1, got $alphaDl" }
        require(alphaDh > alphaDl) {
            "AdaptiveUnsharpMask requires alphaDh > alphaDl, got $alphaDh and $alphaDl"
        }
        require(mu >= 0.0) { "AdaptiveUnsharpMask requires mu >= 0, got $mu" }
        require(beta > 0.0 && beta < 1.0) { "AdaptiveUnsharpMask requires beta in (0, 1), got $beta" }
        require(maxGain > 0.0) { "AdaptiveUnsharpMask requires maxGain > 0, got $maxGain" }
    }

    override val metadata: AlgorithmMetadata = AlgorithmMetadata(
        name = "AdaptiveUnsharpMask",
        kind = "Sharpening",
        category = AlgorithmCategory.SHARPENING,
        frameRequirements = FrameRequirements(
            minFrames = 1,
            maxFrames = 1,
            inputFrameType = InputFrameType.SINGLE,
        ),
        description = "Single-frame directional unsharp masking with Gauss-Newton adapted gains.",
    )

    override fun process(input: AlgorithmInput): AlgorithmOutput {
        require(input.frames.isNotEmpty()) { "AdaptiveUnsharpMask requires at least one frame" }

        val src = input.frames.first()

        val (out, processMs) = measureMs {
            AdaptiveDirectionalUnsharpMask.sharpen(
                src = src,
                width = input.width,
                height = input.height,
                tau1 = tau1,
                tau2 = tau2,
                alphaB = alphaB,
                alphaDl = alphaDl,
                alphaDh = alphaDh,
                mu = mu,
                beta = beta,
                maxGain = maxGain,
            )
        }

        return AlgorithmOutput(
            pixels = out,
            width = input.width,
            height = input.height,
            totalTime = processMs,
        )
    }
}
