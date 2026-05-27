package com.kbbench.algorithm.impl

import com.kbbench.algorithm.base.AlgorithmCategory
import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.AlgorithmMetadata
import com.kbbench.algorithm.base.AlgorithmOutput
import com.kbbench.algorithm.base.FrameRequirements
import com.kbbench.algorithm.base.ImageAlgorithm
import com.kbbench.algorithm.base.InputFrameType
import com.kbbench.algorithm.base.calculateQualityMetrics
import com.kbbench.algorithm.base.measureMs

/**
 * Single-frame contrast stretching algorithm.
 *
 * Operates on [AlgorithmInput.frames]`.first()`. For each RGB channel independently,
 * finds the min and max value across all pixels and linearly stretches it to `[0, 255]`.
 * Alpha is preserved. If a channel is flat (min == max), it is left unchanged.
 */
class ContrastStretching : ImageAlgorithm {
    override val metadata: AlgorithmMetadata = AlgorithmMetadata(
        name = "ContrastStretching",
        category = AlgorithmCategory.CONTRAST_ENHANCEMENT,
        frameRequirements = FrameRequirements(
            minFrames = 1,
            maxFrames = 1,
            inputFrameType = InputFrameType.SINGLE,
        ),
        description = "Single-frame per-channel linear contrast stretching.",
    )

    override fun process(input: AlgorithmInput): AlgorithmOutput {
        require(input.frames.isNotEmpty()) { "ContrastStretching requires at least one frame" }

        val src = input.frames.first()
        val out = IntArray(src.size)

        val (_, processMs) = measureMs {
            var rMin = 255; var rMax = 0
            var gMin = 255; var gMax = 0
            var bMin = 255; var bMax = 0

            for (i in src.indices) {
                val p = src[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (b < bMin) bMin = b; if (b > bMax) bMax = b
            }

            val rRange = rMax - rMin
            val gRange = gMax - gMin
            val bRange = bMax - bMin

            for (i in src.indices) {
                val p = src[i]
                val a = (p shr 24) and 0xFF
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val nr = if (rRange == 0) r else ((r - rMin) * 255) / rRange
                val ng = if (gRange == 0) g else ((g - gMin) * 255) / gRange
                val nb = if (bRange == 0) b else ((b - bMin) * 255) / bRange

                out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        val qualityMetrics = calculateQualityMetrics(
            referencePixels = src,
            candidatePixels = out,
        )

        return AlgorithmOutput(
            pixels = out,
            width = input.width,
            height = input.height,
            totalTime = processMs,
            psnr = qualityMetrics.psnr,
            ssim = qualityMetrics.ssim,
        )
    }
}


