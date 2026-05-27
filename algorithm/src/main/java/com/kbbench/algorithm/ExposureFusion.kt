package com.kbbench.algorithm

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
        category = AlgorithmCategory.HDR_FUSION,
        frameRequirements = FrameRequirements(
            minFrames = 2,
            inputFrameType = InputFrameType.EXPOSURE_BRACKET,
        ),
        description = "Multi-frame exposure fusion weighted by mid-tone luminance.",
    )

    override fun process(input: AlgorithmInput): AlgorithmOutput {
        require(input.frames.size >= 2) {
            "ExposureFusion requires at least 2 frames, got ${input.frames.size}"
        }

        val frames = input.frames
        val n = frames[0].size
        val out = IntArray(n)

        val (_, processMs) = measureMs {
            val mean = 128.0
            val sigma = 64.0
            val twoSigmaSq = 2.0 * sigma * sigma

            for (i in 0 until n) {
                var sumW = 0.0
                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0

                for (f in frames.indices) {
                    val p = frames[f][i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val lum = 0.299 * r + 0.587 * g + 0.114 * b
                    val d = lum - mean
                    val w = exp(-(d * d) / twoSigmaSq)
                    sumW += w
                    sumR += w * r
                    sumG += w * g
                    sumB += w * b
                }

                val a = (frames[0][i] shr 24) and 0xFF
                val nr: Int
                val ng: Int
                val nb: Int
                if (sumW > 0.0) {
                    nr = (sumR / sumW).toInt().coerceIn(0, 255)
                    ng = (sumG / sumW).toInt().coerceIn(0, 255)
                    nb = (sumB / sumW).toInt().coerceIn(0, 255)
                } else {
                    val p = frames[0][i]
                    nr = (p shr 16) and 0xFF
                    ng = (p shr 8) and 0xFF
                    nb = p and 0xFF
                }
                out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        return AlgorithmOutput(
            pixels = out,
            width = input.width,
            height = input.height,
            timings = PhaseTiming(
                alignMs = 0L,
                processMs = processMs,
                tonemapMs = 0L,
                totalMs = processMs,
            ),
        )
    }
}

