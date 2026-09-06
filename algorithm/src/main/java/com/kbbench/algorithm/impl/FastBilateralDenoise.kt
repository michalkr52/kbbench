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
import com.kbbench.algorithm.filter.BilateralGrid

/**
 * Single-frame edge-preserving denoising, Paris and Durand (ECCV 2006). Runs on
 * [AlgorithmInput.frames]`.first()` and preserves alpha. [BilateralGrid] carries the construction
 * and the departures from the paper, colour handling in particular.
 *
 * Defaults are `sigmaSpatial = 16` and `sigmaRange = 0.1`, the pair the paper uses throughout its
 * accuracy and timing evaluation (Figs. 2 to 4).
 *
 * @param sigmaSpatial Spatial standard deviation in pixels, strictly positive. Doubles as the
 *   grid's sampling rate, so raising it makes the filter cheaper rather than costlier.
 * @param sigmaRange Range standard deviation in the paper's normalized units, intensities in
 *   `[0, 1]`, scaled internally to the 8-bit range. Must be positive. Comparable to the square root
 *   of [GuidedFilterDenoise]'s `eps`: both set the contrast below which variation counts as noise,
 *   and He et al. pair `eps = t^2` with `sigmaRange = t` wherever they compare the two filters.
 * @throws IllegalArgumentException if either sigma is not positive.
 */
class FastBilateralDenoise(
    private val sigmaSpatial: Double = 16.0,
    sigmaRange: Double = 0.1,
) : ImageAlgorithm {

    init {
        require(sigmaSpatial > 0.0) { "FastBilateral requires sigmaSpatial > 0, got $sigmaSpatial" }
        require(sigmaRange > 0.0) { "FastBilateral requires sigmaRange > 0, got $sigmaRange" }
    }

    private val scaledSigmaRange: Double = sigmaRange * 255.0

    override val metadata: AlgorithmMetadata = AlgorithmMetadata(
        name = "FastBilateral",
        kind = "Denoise",
        category = AlgorithmCategory.DENOISE,
        frameRequirements = FrameRequirements(
            minFrames = 1,
            maxFrames = 1,
            inputFrameType = InputFrameType.SINGLE,
        ),
        description = "Single-frame bilateral denoising approximated on a downsampled bilateral grid.",
        parameters = listOf(
            AlgorithmParameter(
                id = "sigmaSpatial",
                label = "Spatial sigma",
                default = 16.0,
                min = 2.0,
                max = 64.0,
                step = 1.0,
                description = "Kernel width in pixels; raising it blurs a wider area and runs faster.",
            ),
            AlgorithmParameter(
                id = "sigmaRange",
                label = "Range sigma",
                default = 0.1,
                min = 0.01,
                max = 0.5,
                step = 0.01,
                description = "Contrast difference treated as noise; raising it blurs across stronger edges.",
            ),
        ),
    )

    override fun withParameters(values: Map<String, Double>): ImageAlgorithm = FastBilateralDenoise(
        sigmaSpatial = metadata.parameters.resolve(values, "sigmaSpatial"),
        sigmaRange = metadata.parameters.resolve(values, "sigmaRange"),
    )

    override fun process(input: AlgorithmInput): AlgorithmOutput {
        require(input.frames.isNotEmpty()) { "FastBilateral requires at least one frame" }

        val src = input.frames.first()

        val (out, processMs) = measureMs {
            BilateralGrid.filter(
                src = src,
                width = input.width,
                height = input.height,
                sigmaSpatial = sigmaSpatial,
                sigmaRange = scaledSigmaRange,
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
