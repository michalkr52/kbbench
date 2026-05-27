package com.kbbench.algorithm.base

/**
 * Immutable input passed to every [ImageAlgorithm].
 *
 * @property frames List of frames; each frame is a flat array of ARGB_8888 pixels
 *   packed into [Int] in row-major order (size = [width] * [height]).
 * @property width Frame width in pixels (shared by all frames).
 * @property height Frame height in pixels (shared by all frames).
 * @property exposureTimes Exposure time per frame, in nanoseconds.
 * @property isoValues ISO sensitivity per frame.
 * @property captureTimeMs Wall-clock time spent capturing the frames, measured
 *   externally by the caller before invoking the algorithm.
 */
data class AlgorithmInput(
    val frames: List<IntArray>,
    val width: Int,
    val height: Int,
    val exposureTimes: List<Long>,
    val isoValues: List<Int>,
    val captureTimeMs: Long,
)


