package com.kbbench.algorithm.base

/**
 * Immutable input passed to every [ImageAlgorithm].
 *
 * @property frames One or more [Frame]s of the same geometry, in capture order.
 * @property exposureTimes Exposure time per frame, in nanoseconds.
 * @property isoValues ISO sensitivity per frame.
 * @property captureTimeMs Wall-clock time spent capturing the frames, measured externally by the
 *   caller before invoking the algorithm.
 */
data class AlgorithmInput(
    val frames: List<Frame>,
    val exposureTimes: List<Long>,
    val isoValues: List<Int>,
    val captureTimeMs: Long,
) {
    init {
        require(frames.isNotEmpty()) { "AlgorithmInput requires at least one frame" }
        val first = frames.first()
        require(frames.all { it.width == first.width && it.height == first.height }) {
            "All frames must share one geometry, got ${frames.map { "${it.width}x${it.height}" }}"
        }
    }

    /** Geometry shared by every frame. */
    val width: Int get() = frames.first().width

    val height: Int get() = frames.first().height
}
