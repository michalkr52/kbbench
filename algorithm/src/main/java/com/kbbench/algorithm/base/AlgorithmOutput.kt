package com.kbbench.algorithm.base

/**
 * Result produced by an [ImageAlgorithm].
 *
 * @property frame Output image, same geometry as the input.
 * @property totalTime Total processing time of the algorithm, in milliseconds.
 */
data class AlgorithmOutput(
    val frame: Frame,
    val totalTime: Long,
) {
    val width: Int get() = frame.width

    val height: Int get() = frame.height
}
