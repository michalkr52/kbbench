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
 * Single-frame edge-preserving denoising, He, Sun and Tang (ECCV 2010), Eq. 19: the full RGB frame
 * guides the filter and a 3x3 system is solved per pixel instead of treating channels
 * independently. Runs on [AlgorithmInput.frames]`.first()` and preserves alpha. [GuidedFilter] lists
 * the departures from the paper.
 *
 * @param radius Window half-width in pixels, at least 1; the window is `(2 * radius + 1)` square.
 * @param eps Regularization in the paper's normalized units, intensities in `[0, 1]` — the authors
 *   quote `0.1^2`, `0.2^2`, `0.4^2` — scaled internally to the 8-bit range so published values can
 *   be used verbatim. Must be positive; larger smooths more.
 * @throws IllegalArgumentException if [radius] or `eps` is out of range.
 */
class GuidedFilterColorDenoise(
    private val radius: Int = 4,
    eps: Double = 0.04 * 0.04,
) : ImageAlgorithm {

    init {
        require(radius >= 1) { "GuidedFilterColor requires radius >= 1, got $radius" }
        require(eps > 0.0) { "GuidedFilterColor requires eps > 0, got $eps" }
    }

    private val scaledEps: Double = eps * 255.0 * 255.0

    override val metadata: AlgorithmMetadata = AlgorithmMetadata(
        name = "GuidedFilterColor",
        kind = "Denoise",
        category = AlgorithmCategory.DENOISE,
        frameRequirements = FrameRequirements(
            minFrames = 1,
            maxFrames = 1,
            inputFrameType = InputFrameType.SINGLE,
        ),
        description = "Single-frame edge-preserving denoising, guided by the full RGB frame.",
    )

    override fun process(input: AlgorithmInput): AlgorithmOutput {
        require(input.frames.isNotEmpty()) { "GuidedFilterColor requires at least one frame" }

        val src = input.frames.first()

        val (out, processMs) = measureMs {
            GuidedFilter.filterColor(
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
