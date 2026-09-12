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
        require(input.frames.isNotEmpty()) { "ContrastStretching requires at least one frame" }

        val src = input.frames.first()
        val out = IntArray(src.size)

        val (_, processMs) = measureMs {
            var rMin = 1f; var rMax = 0f
            var gMin = 1f; var gMax = 0f
            var bMin = 1f; var bMax = 0f

            for (i in src.indices) {
                val p = src[i]
                val r = Pixels.red(p)
                val g = Pixels.green(p)
                val b = Pixels.blue(p)
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (b < bMin) bMin = b; if (b > bMax) bMax = b
            }

            val rRange = rMax - rMin
            val gRange = gMax - gMin
            val bRange = bMax - bMin

            for (i in src.indices) {
                val p = src[i]
                val r = Pixels.red(p)
                val g = Pixels.green(p)
                val b = Pixels.blue(p)

                out[i] = Pixels.pack(
                    source = p,
                    r = if (rRange == 0f) r else (r - rMin) / rRange,
                    g = if (gRange == 0f) g else (g - gMin) / gRange,
                    b = if (bRange == 0f) b else (b - bMin) / bRange,
                )
            }
        }

        return AlgorithmOutput(
            pixels = out,
            width = input.width,
            height = input.height,
            totalTime = processMs,
        )
    }
}
