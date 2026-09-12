package com.kbbench.algorithm.impl

import com.kbbench.algorithm.base.AlgorithmCategory
import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.AlgorithmMetadata
import com.kbbench.algorithm.base.AlgorithmOutput
import com.kbbench.algorithm.base.AlgorithmPreprocessingGuidance
import com.kbbench.algorithm.base.FrameRequirements
import com.kbbench.algorithm.base.ImageAlgorithm
import com.kbbench.algorithm.base.InputFrameType
import com.kbbench.algorithm.base.Pixels
import com.kbbench.algorithm.base.measureMs
import com.kbbench.algorithm.preprocessing.TransferEncoding
import kotlin.math.exp

/**
 * Multi-frame exposure fusion algorithm.
 *
 * For each pixel position, each frame contributes a Gaussian weight based on its luminance
 * (`0.299R + 0.587G + 0.114B`), peaking around mid-grey (mean = 128, sigma = 64). The output
 * pixel is the weighted average of the input pixels across frames, normalized by total weight.
 * Alpha is taken from the first frame.
 *
 * Throws [IllegalArgumentException] if fewer than two frames are provided.
 */
class ExposureFusion : ImageAlgorithm {
    override val metadata: AlgorithmMetadata = AlgorithmMetadata(
        name = "ExposureFusion",
        kind = "HDR",
        category = AlgorithmCategory.HDR_FUSION,
        frameRequirements = FrameRequirements(
            minFrames = 2,
            inputFrameType = InputFrameType.EXPOSURE_BRACKET,
        ),
        description = "Exposure fusion weighted by mid-tone luminance",
        preprocessingGuidance = AlgorithmPreprocessingGuidance(
            recommendedTransfer = TransferEncoding.SRGB,
        ),
    )

    override fun process(input: AlgorithmInput): AlgorithmOutput {
        require(input.frames.size >= 2) {
            "ExposureFusion requires at least 2 frames, got ${input.frames.size}"
        }

        val frames = input.frames
        val n = frames[0].size
        val out = IntArray(n)

        val (_, processMs) = measureMs {
            val twoSigmaSq = 2.0 * WELL_EXPOSED_SIGMA * WELL_EXPOSED_SIGMA

            for (i in 0 until n) {
                var sumW = 0.0
                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0

                for (f in frames.indices) {
                    val p = frames[f][i]
                    val d = Pixels.luma(p) - WELL_EXPOSED_MEAN
                    val w = exp(-(d * d) / twoSigmaSq)
                    sumW += w
                    sumR += w * Pixels.red(p)
                    sumG += w * Pixels.green(p)
                    sumB += w * Pixels.blue(p)
                }

                val reference = frames[0][i]
                out[i] = if (sumW > 0.0) {
                    Pixels.pack(
                        source = reference,
                        r = (sumR / sumW).toFloat(),
                        g = (sumG / sumW).toFloat(),
                        b = (sumB / sumW).toFloat(),
                    )
                } else {
                    reference
                }
            }
        }

        return AlgorithmOutput(
            pixels = out,
            width = input.width,
            height = input.height,
            totalTime = processMs,
        )
    }

    private companion object {
        /**
         * The 8-bit `128` and `64` this algorithm has always used, carried across unchanged so the
         * move to normalized units is behaviour-preserving. Mertens, Kautz and Van Reeth (2007)
         * specify `0.2` for the well-exposedness term; expressing the constants here makes that
         * divergence visible, but changing it is a tuning decision, not a units one.
         */
        const val WELL_EXPOSED_MEAN = 128.0 / 255.0
        const val WELL_EXPOSED_SIGMA = 64.0 / 255.0
    }
}
