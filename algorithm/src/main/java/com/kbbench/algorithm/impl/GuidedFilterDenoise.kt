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
 * Single-frame edge-preserving denoising, He, Sun and Tang (ECCV 2010), Algorithm 1 applied to each
 * RGB channel with the channel guiding itself. Runs on [AlgorithmInput.frames]`.first()` and
 * preserves alpha. [GuidedFilter] lists the departures from the paper.
 *
 * @param radius Window half-width in pixels, at least 1; the window is `(2 * radius + 1)` square.
 * @param eps Regularization in the paper's normalized units, intensities in `[0, 1]` — the authors
 *   quote `0.1^2`, `0.2^2`, `0.4^2` — scaled internally to the 8-bit range so published values can
 *   be used verbatim. Must be positive; larger smooths more.
 * @throws IllegalArgumentException if [radius] or `eps` is out of range.
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
