package com.kbbench.algorithm.impl

import com.kbbench.algorithm.base.ImageAlgorithm

/**
 * Registry of all [ImageAlgorithm] implementations available in this module.
 */
class AlgorithmRegistry {

    private val algorithms: List<ImageAlgorithm> = listOf(
        ContrastStretching(),
        ExposureFusion(),
        GuidedFilterDenoise(),
        GuidedFilterColorDenoise(),
    )

    /** Returns all registered algorithms. */
    fun getAll(): List<ImageAlgorithm> = algorithms

    /**
     * Returns the algorithm whose [ImageAlgorithm.name] matches [name].
     *
     * @throws IllegalArgumentException if no algorithm with the given name is registered.
     */
    fun getByName(name: String): ImageAlgorithm =
        algorithms.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown algorithm: $name")
}


