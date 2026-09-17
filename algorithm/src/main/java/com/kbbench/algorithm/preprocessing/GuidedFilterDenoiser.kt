package com.kbbench.algorithm.preprocessing

import com.kbbench.algorithm.base.AlgorithmParameter
import com.kbbench.algorithm.base.Denoiser
import com.kbbench.algorithm.base.DenoiserDescriptor
import com.kbbench.algorithm.base.Frame
import com.kbbench.algorithm.base.FrameDomain
import com.kbbench.algorithm.base.FrameStorage
import com.kbbench.algorithm.base.resolve
import com.kbbench.algorithm.filter.GuidedFilter
import kotlin.math.roundToInt

/** Guided filtering at the fixed denoiser boundary. */
class GuidedFilterDenoiser(
    private val radius: Int = 8,
    private val eps: Double = 0.2 * 0.2,
    private val colorGuidance: Boolean = false,
) : Denoiser {
    init {
        require(radius >= 1) { "GuidedFilterDenoiser requires radius >= 1, got $radius" }
        require(eps > 0.0) { "GuidedFilterDenoiser requires eps > 0, got $eps" }
    }

    override val descriptor: DenoiserDescriptor = DenoiserDescriptor(
        id = if (colorGuidance) "guided_filter_color" else "guided_filter",
        name = if (colorGuidance) "Guided Filter Color" else "Guided Filter",
        acceptedDomains = FrameDomain.entries.toSet(),
        acceptedStorage = FrameStorage.entries.toSet(),
        outputStorage = FrameStorage.UNCLIPPED_FLOAT,
        parameters = listOf(
            AlgorithmParameter(
                id = "radius",
                label = "Radius",
                default = 8.0,
                min = 1.0,
                max = 32.0,
                step = 1.0,
            ),
            AlgorithmParameter(
                id = "eps",
                label = "Epsilon",
                default = 0.2 * 0.2,
                min = 0.001,
                max = 0.25,
                step = 0.001,
            ),
        ),
    )

    override val effectiveParameters: Map<String, Double>
        get() = mapOf(
            "radius" to radius.toDouble(),
            "eps" to eps,
        )

    override fun withParameters(values: Map<String, Double>): Denoiser = if (values.isEmpty()) {
        this
    } else {
        GuidedFilterDenoiser(
            radius = descriptor.parameters.resolve(values, "radius").roundToInt(),
            eps = descriptor.parameters.resolve(values, "eps"),
            colorGuidance = colorGuidance,
        )
    }

    override fun process(input: Frame): Frame {
        return if (colorGuidance) {
            GuidedFilter.filterColor(
                input,
                radius,
                eps,
                outputStorage = FrameStorage.UNCLIPPED_FLOAT,
            )
        } else {
            GuidedFilter.filterGray(
                input,
                radius,
                eps,
                outputStorage = FrameStorage.UNCLIPPED_FLOAT,
            )
        }
    }
}
