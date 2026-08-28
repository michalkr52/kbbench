package com.kbbench.algorithm.impl

import com.kbbench.algorithm.base.AlgorithmCategory
import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.AlgorithmMetadata
import com.kbbench.algorithm.base.AlgorithmOutput
import com.kbbench.algorithm.base.FrameRequirements
import com.kbbench.algorithm.base.ImageAlgorithm
import com.kbbench.algorithm.base.InputFrameType
import com.kbbench.algorithm.base.measureMs
import com.kbbench.algorithm.filter.GuidedFilter

/**
 * Single-frame edge-preserving denoising with the guided filter of He, Sun and Tang (ECCV 2010).
 *
 * Operates on [AlgorithmInput.frames]`.first()`, applying the scalar filter of Algorithm 1 to each
 * RGB channel independently with the channel guiding itself. Flat regions collapse towards the
 * local mean while edges are left close to the input, which is the behaviour described in
 * Section 3.2. Alpha is preserved.
 *
 * @param radius Window half-width in pixels; the window is `(2 * radius + 1)` square.
 * @param eps Regularization in the normalized units of the paper, where intensities lie in `[0, 1]`
 *   (the authors quote values such as `0.1^2`, `0.2^2`, `0.4^2`). Scaled internally to the 8-bit
 *   range so published parameters can be used verbatim. Larger values smooth more aggressively.
 */
class GuidedFilterDenoise(
    private val radius: Int = 4,
    eps: Double = 0.04 * 0.04,
) : ImageAlgorithm {

    init {
        require(radius >= 1) { "GuidedFilter requires radius >= 1, got $radius" }
        require(eps > 0.0) { "GuidedFilter requires eps > 0, got $eps" }
    }

    private val scaledEps: Double = eps * 255.0 * 255.0

    override val metadata: AlgorithmMetadata = AlgorithmMetadata(
        name = "GuidedFilter",
        kind = "Denoise",
        category = AlgorithmCategory.DENOISE,
        frameRequirements = FrameRequirements(
            minFrames = 1,
            maxFrames = 1,
            inputFrameType = InputFrameType.SINGLE,
        ),
        description = "Single-frame edge-preserving denoising, per-channel self-guided filter.",
    )

    override fun process(input: AlgorithmInput): AlgorithmOutput {
        require(input.frames.isNotEmpty()) { "GuidedFilter requires at least one frame" }

        val src = input.frames.first()

        val (out, processMs) = measureMs {
            GuidedFilter.filterGray(
                src = src,
                width = input.width,
                height = input.height,
                radius = radius,
                eps = scaledEps,
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
