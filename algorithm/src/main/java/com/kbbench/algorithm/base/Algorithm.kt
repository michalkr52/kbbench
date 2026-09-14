package com.kbbench.algorithm.base

/**
 * Common contract for every image processing algorithm benchmarked by kbbench.
 *
 * Implementations must be pure Kotlin/JVM and operate on ARGB_8888 pixels packed
 * into [Int] values. They are expected to be stateless and thread-confined per call.
 */
interface ImageAlgorithm {
    /** Structured description used by UI, filtering and validation logic. */
    val metadata: AlgorithmMetadata

    /** Human-readable, unique identifier used by the registry and UI. */
    val name: String
        get() = metadata.name

    /** Runs the algorithm on the supplied [input] and returns the produced frame and timings. */
    fun process(input: AlgorithmInput): AlgorithmOutput

    /**
     * Returns an instance configured with the supplied tuning [values], keyed by
     * [AlgorithmParameter.id]. Missing or out-of-range entries fall back to the declared defaults.
     *
     * Implementations declaring parameters must override this; the default suits algorithms with
     * no tuning knobs.
     */
    fun withParameters(values: Map<String, Double>): ImageAlgorithm = this
}
