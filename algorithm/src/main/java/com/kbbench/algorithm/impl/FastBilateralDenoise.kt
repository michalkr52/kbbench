package com.kbbench.algorithm.impl

import com.kbbench.algorithm.base.AlgorithmCategory
import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.AlgorithmMetadata
import com.kbbench.algorithm.base.AlgorithmOutput
import com.kbbench.algorithm.base.FrameRequirements
import com.kbbench.algorithm.base.ImageAlgorithm
import com.kbbench.algorithm.base.InputFrameType
import com.kbbench.algorithm.base.measureMs
import com.kbbench.algorithm.filter.BilateralGrid

/**
 * Single-frame edge-preserving denoising, Paris and Durand (ECCV 2006). Runs on
 * [AlgorithmInput.frames]`.first()` and preserves alpha. [BilateralGrid] carries the construction
 * and the departures from the paper, colour handling in particular.
 *
 * @param sigmaSpatial Spatial standard deviation in pixels, strictly positive. Doubles as the
 *   grid's sampling rate, so raising it makes the filter cheaper rather than costlier.
 * @param sigmaRange Range standard deviation in the paper's normalized units, intensities in
 *   `[0, 1]`, scaled internally to the 8-bit range. Must be positive. Comparable to the square root
 *   of [GuidedFilterDenoise]'s `eps`: both set the contrast below which variation counts as noise.
 * @throws IllegalArgumentException if either sigma is not positive.
 */
class FastBilateralDenoise(
    private val sigmaSpatial: Double = 3.0,
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
