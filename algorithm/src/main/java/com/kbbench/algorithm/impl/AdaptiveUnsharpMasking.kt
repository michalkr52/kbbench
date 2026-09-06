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
 * Single-frame adaptive unsharp masking (Polesel, Ramponi, Mathews, IEEE TIP 9(3), 2000).
 *
 * Equations and departures from the paper are documented on [AdaptiveDirectionalUnsharpMask], which
 * does the work; this class only validates parameters and reports timing.
 *
 * Defaults are Table I of the paper, tuned there on a 256x256 crop of Lena. [tau1] tracks the
 * input's noise level (the paper quotes `[30, 60]`) and may need raising for noisier captures.
 *
 * @param tau1 Local-variance threshold below which a pixel counts as smooth; `>= 0`.
 * @param tau2 Threshold at or above which a pixel counts as high-contrast; must exceed [tau1].
 * @param alphaB Dynamics multiplier in smooth areas; `>= 1`, where `1` means no enhancement.
 * @param alphaDl Multiplier in high-contrast areas; must exceed `1`.
 * @param alphaDh Multiplier in medium-contrast areas; must exceed [alphaDl].
 * @param mu Gauss-Newton step size, `>= 0`.
 * @param beta Forgetting factor of the autocorrelation recursion, inside `(0, 1)`.
 * @param maxGain Clamp on either directional gain; must be positive.
 * @throws IllegalArgumentException if any parameter falls outside the above.
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
