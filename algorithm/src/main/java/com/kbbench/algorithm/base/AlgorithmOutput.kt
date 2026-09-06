package com.kbbench.algorithm.base

/**
 * Result produced by an [ImageAlgorithm].
 *
 * @property pixels Output frame as a flat array of ARGB_8888 pixels packed into [Int]
 *   (size = [width] * [height]), same format as [AlgorithmInput.frames].
 * @property width Output frame width in pixels.
 * @property height Output frame height in pixels.
 * @property totalTime Total processing time of the algorithm, in milliseconds.
 */
data class AlgorithmOutput(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val totalTime: Long,
)
