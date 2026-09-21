package com.kbbench.algorithm.impl

import com.kbbench.algorithm.base.AlgorithmCategory
import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.AlgorithmMetadata
import com.kbbench.algorithm.base.AlgorithmOutput
import com.kbbench.algorithm.base.AlgorithmPreprocessingGuidance
import com.kbbench.algorithm.base.FrameRequirements
import com.kbbench.algorithm.base.ImageAlgorithm
import com.kbbench.algorithm.base.InputFrameType
import com.kbbench.algorithm.base.measureMs

/**
 * Single-frame contrast stretching algorithm.
 *
 * Operates on [AlgorithmInput.frames]`.first()`. For each RGB channel independently,
 * finds the min and max value across all pixels and linearly stretches it to the full range.
 * Alpha is preserved. If a channel is flat (min == max), it is left unchanged.
 */
class ContrastStretching : ImageAlgorithm {
    override val metadata: AlgorithmMetadata = AlgorithmMetadata(
        name = "ContrastStretching",
        kind = "Contrast",
        category = AlgorithmCategory.CONTRAST_ENHANCEMENT,
        frameRequirements = FrameRequirements(
            minFrames = 1,
            maxFrames = 1,
            inputFrameType = InputFrameType.SINGLE,
        ),
        description = "Per-channel linear contrast stretching",
    )

    override fun process(input: AlgorithmInput): AlgorithmOutput {
        val src = input.frames.first()
        val out = src.emptyLike()

        val (_, processMs) = measureMs {
            for (channel in 0 until 3) {
                var min = 1f
                var max = 0f
                for (i in 0 until src.size) {
                    val v = src.sample(channel, i)
                    if (v < min) min = v
                    if (v > max) max = v
                }

                val range = max - min
                for (i in 0 until src.size) {
                    val v = src.sample(channel, i)
                    out.setSample(channel, i, if (range == 0f) v else (v - min) / range)
                }
            }
        }

        return AlgorithmOutput(frame = out, totalTime = processMs)
    }
}
