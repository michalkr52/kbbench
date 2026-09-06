package com.kbbench.algorithm.impl

import com.kbbench.algorithm.base.AlgorithmCategory
import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.AlgorithmMetadata
import com.kbbench.algorithm.base.AlgorithmOutput
import com.kbbench.algorithm.base.AlgorithmParameter
import com.kbbench.algorithm.base.FrameRequirements
import com.kbbench.algorithm.base.ImageAlgorithm
import com.kbbench.algorithm.base.InputFrameType
import com.kbbench.algorithm.base.measureMs
import com.kbbench.algorithm.base.resolve
import com.kbbench.algorithm.filter.GuidedFilter
import kotlin.math.roundToInt

/**
 * Single-frame edge-preserving denoising, He, Sun and Tang (ECCV 2010), Algorithm 1 applied to each
 * RGB channel with the channel guiding itself. Runs on [AlgorithmInput.frames]`.first()` and
 * preserves alpha. [GuidedFilter] lists the departures from the paper.
 *
 * Defaults are the paper's own denoising configuration, `r = 8` and `eps = 0.2^2` of Fig. 8.
 *
 * @param radius Window half-width in pixels, at least 1; the window is `(2 * radius + 1)` square.
 * @param eps Regularization in the paper's normalized units, intensities in `[0, 1]`, scaled
 *   internally to the 8-bit range so published values can be used verbatim. Must be positive;
 *   larger smooths more. Section 3.2 notes it plays the role the range variance `sigmaRange^2`
 *   plays in the bilateral filter, and the paper pairs `eps = t^2` with `sigmaRange = t` wherever
 *   it compares the two.
 * @throws IllegalArgumentException if [radius] or `eps` is out of range.
 */
class GuidedFilterDenoise(
    private val radius: Int = 8,
    eps: Double = 0.2 * 0.2,
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
        parameters = listOf(
            AlgorithmParameter(
                id = "radius",
                label = "Radius",
                default = 8.0,
                min = 1.0,
                max = 32.0,
                step = 1.0,
                description = "Window half-width in pixels; raising it smooths across a wider area.",
            ),
            AlgorithmParameter(
                id = "eps",
                label = "Epsilon",
                default = 0.2 * 0.2,
                min = 0.001,
                max = 0.25,
                step = 0.001,
                description = "Smoothing strength; raising it blurs more, lowering it preserves more detail.",
            ),
        ),
    )

    override fun withParameters(values: Map<String, Double>): ImageAlgorithm = GuidedFilterDenoise(
        radius = metadata.parameters.resolve(values, "radius").roundToInt(),
        eps = metadata.parameters.resolve(values, "eps"),
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
