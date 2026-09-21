package com.kbbench.algorithm.impl

import com.kbbench.algorithm.base.AlgorithmCategory
import com.kbbench.algorithm.base.AlgorithmInput
import com.kbbench.algorithm.base.AlgorithmMetadata
import com.kbbench.algorithm.base.AlgorithmOutput
import com.kbbench.algorithm.base.AlgorithmParameter
import com.kbbench.algorithm.base.AlgorithmPreprocessingGuidance
import com.kbbench.algorithm.base.FrameRequirements
import com.kbbench.algorithm.base.ImageAlgorithm
import com.kbbench.algorithm.base.InputFrameType
import com.kbbench.algorithm.base.Frame
import com.kbbench.algorithm.base.measureMs
import com.kbbench.algorithm.base.resolve
import com.kbbench.algorithm.preprocessing.TransferEncoding
import kotlin.math.max
import kotlin.math.min

/**
 * Classical unsharp masking, Eq. (1) and (2) of Polesel, Ramponi and Mathews (IEEE TIP 9(3), 2000),
 * with the `lambda = 0.5` of Table I. This is the baseline [AdaptiveUnsharpMasking] is compared
 * against in Fig. 5(a).
 * ```
 * z(n,m) = 4*x(n,m) - x(n-1,m) - x(n+1,m) - x(n,m-1) - x(n,m+1)
 * y(n,m) = x(n,m) + lambda * z(n,m)
 * ```
 * Eq. (3) and (4) sum to exactly this `z`, so the adaptive filter is this algorithm with the fixed
 * gain replaced by an adapted one; running both isolates the contribution of the adaptation.
 *
 * ### Departures from the paper
 *
 * - The paper processes 8-bit grayscale. Here `z` is computed on luma and the single correction is
 *   added to R, G and B alike, matching [AdaptiveDirectionalUnsharpMask] so that a benchmark
 *   comparing the two measures the algorithms rather than differences in their scaffolding.
 * - Missing border neighbours replicate, so their terms vanish.
 *
 * There is no separate `filter/` object because there is no intermediate state to extract. Luma is
 * held in three rolling rows rather than a full plane, which keeps memory at `O(width)` and computes
 * each pixel's luma exactly once; recomputing it per tap would quintuple the arithmetic and show up
 * as a runtime difference unrelated to the algorithm.
 *
 * @param lambda Gain applied to the highpass response; `>= 0`, where `0` reduces Eq. (1) to `y = x`.
 * @throws IllegalArgumentException if [lambda] is negative.
 */
class LinearUnsharpMasking(
    private val lambda: Double = 0.5,
) : ImageAlgorithm {

    init {
        require(lambda >= 0.0) { "LinearUnsharpMask requires lambda >= 0, got $lambda" }
    }

    override val metadata: AlgorithmMetadata = AlgorithmMetadata(
        name = "LinearUnsharpMask",
        kind = "Sharpening",
        category = AlgorithmCategory.SHARPENING,
        frameRequirements = FrameRequirements(
            minFrames = 1,
            maxFrames = 1,
            inputFrameType = InputFrameType.SINGLE,
        ),
        description = "Unsharp masking with a fixed 4-neighbour Laplacian gain",
        preprocessingGuidance = AlgorithmPreprocessingGuidance(
            recommendedTransfer = TransferEncoding.SRGB,
        ),
        parameters = listOf(
            AlgorithmParameter(
                id = "lambda",
                label = "Lambda",
                default = 0.5,
                min = 0.0,
                max = 3.0,
                step = 0.05,
                description = "Sharpening strength.\nIncreasing it applies more sharpening.",
            ),
        ),
    )

    override fun withParameters(values: Map<String, Double>): ImageAlgorithm = LinearUnsharpMasking(
        lambda = metadata.parameters.resolve(values, "lambda"),
    )

    override fun process(input: AlgorithmInput): AlgorithmOutput {
        val src = input.frames.first()
        val width = src.width
        val height = src.height

        val (out, processMs) = measureMs {
            val result = src.emptyLike()

            // Rows y-1, y and y+1 of the luma plane, rotated as the scan advances.
            var above = FloatArray(width)
            var current = FloatArray(width)
            var below = FloatArray(width)

            loadLumaRow(src, above, width, height, 0)
            loadLumaRow(src, current, width, height, 0)
            loadLumaRow(src, below, width, height, 1)

            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    val z = 4f * current[x] -
                        above[x] -
                        below[x] -
                        current[max(0, x - 1)] -
                        current[min(width - 1, x + 1)]
                    val correction = lambda * z

                    val i = row + x
                    result.setSample(0, i, corrected(src.r(i), correction))
                    result.setSample(1, i, corrected(src.g(i), correction))
                    result.setSample(2, i, corrected(src.b(i), correction))
                }

                val recycled = above
                above = current
                current = below
                below = recycled
                loadLumaRow(src, below, width, height, y + 2)
            }

            result
        }

        return AlgorithmOutput(frame = out, totalTime = processMs)
    }

    /** Fills [dst] with row [y] of the luma plane; [y] is clamped, which replicates the border. */
    private fun loadLumaRow(src: Frame, dst: FloatArray, width: Int, height: Int, y: Int) {
        val row = y.coerceIn(0, height - 1) * width
        for (x in 0 until width) {
            dst[x] = src.luma(row + x)
        }
    }

    /** @return [level] with the luma-domain [correction] added, stored at the internal scale. */
    private fun corrected(level: Float, correction: Double): Float =
        (level + correction).toFloat()
}
