package com.kbbench.algorithm

/**
 * Fine-grained timing breakdown for a single algorithm run.
 *
 * Phases that are not applicable to a given algorithm must be reported as `0`.
 *
 * @property alignMs Time spent aligning input frames, in milliseconds.
 * @property processMs Time spent on the main processing phase (merge, fusion, etc.), in milliseconds.
 * @property tonemapMs Time spent on tone-mapping, in milliseconds.
 * @property totalMs Total time including all phases and any overhead, in milliseconds.
 */
data class PhaseTiming(
    val alignMs: Long,
    val processMs: Long,
    val tonemapMs: Long,
    val totalMs: Long,
)

