package com.kbbench.algorithm.base

/**
 * Declares one user-adjustable tuning knob of an [ImageAlgorithm].
 *
 * Ranges must be chosen so that every value in `[min, max]` is accepted by the owning algorithm's
 * constructor, including when other parameters sit at their own extremes: callers pick values
 * independently and no cross-parameter validation happens before construction.
 *
 * @property id Stable key used for persistence, export and [resolve] lookups.
 * @property label Human-readable name shown next to the control.
 * @property default Value the algorithm uses when the parameter is not overridden.
 * @property min Lowest selectable value, inclusive.
 * @property max Highest selectable value, inclusive.
 * @property step Granularity of the control; must be positive and divide the range sensibly.
 * @property description Optional one-line hint about what raising the value does.
 */
data class AlgorithmParameter(
    val id: String,
    val label: String,
    val default: Double,
    val min: Double,
    val max: Double,
    val step: Double,
    val description: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Parameter id must not be blank" }
        require(label.isNotBlank()) { "Parameter label must not be blank for '$id'" }
        require(min < max) { "Parameter '$id' requires min < max, got $min and $max" }
        require(default in min..max) { "Parameter '$id' default $default outside [$min, $max]" }
        require(step > 0.0) { "Parameter '$id' requires step > 0, got $step" }
    }

    /** Number of decimal places needed to display a value at this [step] granularity. */
    val decimals: Int
        get() = when {
            step >= 1.0 -> 0
            step >= 0.1 -> 1
            step >= 0.01 -> 2
            else -> 3
        }
}

/**
 * Reads the value of parameter [id] from [values], falling back to the declared default when it is
 * missing or not finite, and clamping into the declared range.
 *
 * Clamping is deliberate: overrides arrive from persisted user settings that may predate a change
 * to the declared range.
 */
fun List<AlgorithmParameter>.resolve(values: Map<String, Double>, id: String): Double {
    val parameter = firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown parameter '$id'")
    val supplied = values[id]
    if (supplied == null || !supplied.isFinite()) return parameter.default
    return supplied.coerceIn(parameter.min, parameter.max)
}

/** Effective values of every declared parameter: declared defaults overridden by [values]. */
fun List<AlgorithmParameter>.effectiveValues(values: Map<String, Double>): Map<String, Double> =
    associate { it.id to resolve(values, it.id) }
