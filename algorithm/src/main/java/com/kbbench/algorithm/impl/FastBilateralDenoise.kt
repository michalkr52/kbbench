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
 * Single-frame edge-preserving denoising with the fast bilateral filter of Paris and Durand
 * (ECCV 2006), included as the baseline the guided filter is usually measured against.
 *
 * The bilateral filter averages neighbours weighted by both spatial and intensity proximity, so it
 * smooths within regions and not across edges. Paris and Durand recast it as a linear convolution in
 * a downsampled `(x, y, intensity)` grid, which makes it O(N) and independent of the spatial sigma.
 * See [BilateralGrid] for the construction and for how colour is handled.
 *
 * Worth keeping in mind when comparing against [GuidedFilterDenoise]: both are now linear in pixel
 * count, so the interesting differences are output quality and constant factors rather than
 * asymptotics. The bilateral filter is the method He et al. cite for gradient reversal — halos where
 * a pixel sits in a neighbourhood with few similar pixels — which the guided filter is designed to
 * avoid.
 *
 * @param sigmaSpatial Spatial standard deviation in pixels. Doubles as the grid's spatial sampling
 *   rate, so raising it makes the filter *cheaper*, not costlier.
 * @param sigmaRange Range standard deviation in normalized units, intensities in `[0, 1]`, scaled
 *   internally to the 8-bit range. Comparable to the square root of [GuidedFilterDenoise]'s `eps`:
 *   both set the contrast below which variation counts as noise rather than structure.
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
