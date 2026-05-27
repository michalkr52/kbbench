package com.kbbench.algorithm

/**
 * Result produced by an [ImageAlgorithm].
 *
 * @property pixels Output frame as a flat array of ARGB_8888 pixels packed into [Int]
 *   (size = [width] * [height]), same format as [AlgorithmInput.frames].
 * @property width Output frame width in pixels.
 * @property height Output frame height in pixels.
 * @property timings Per-phase timing breakdown of the run.
 */
data class AlgorithmOutput(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val timings: PhaseTiming,
)

