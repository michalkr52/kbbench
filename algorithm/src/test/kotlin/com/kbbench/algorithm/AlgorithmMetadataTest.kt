package com.kbbench.algorithm

import com.kbbench.algorithm.base.AlgorithmCategory
import com.kbbench.algorithm.base.InputFrameType
import com.kbbench.algorithm.impl.AdaptiveUnsharpMasking
import com.kbbench.algorithm.impl.AlgorithmRegistry
import com.kbbench.algorithm.impl.ContrastStretching
import com.kbbench.algorithm.impl.ExposureFusion
import com.kbbench.algorithm.impl.FastBilateralDenoise
import com.kbbench.algorithm.impl.GuidedFilterColorDenoise
import com.kbbench.algorithm.impl.GuidedFilterDenoise
import com.kbbench.algorithm.impl.LinearUnsharpMasking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlgorithmMetadataTest {

    @Test
    fun contrastStretchingExposesSingleFrameMetadata() {
        val algorithm = ContrastStretching()

        assertEquals("ContrastStretching", algorithm.name)
        assertEquals(AlgorithmCategory.CONTRAST_ENHANCEMENT, algorithm.metadata.category)
        assertEquals(1, algorithm.metadata.frameRequirements.minFrames)
        assertEquals(1, algorithm.metadata.frameRequirements.maxFrames)
        assertEquals(InputFrameType.SINGLE, algorithm.metadata.frameRequirements.inputFrameType)
    }

    @Test
    fun exposureFusionExposesBracketedMultiFrameMetadata() {
        val algorithm = ExposureFusion()

        assertEquals("ExposureFusion", algorithm.name)
        assertEquals(AlgorithmCategory.HDR_FUSION, algorithm.metadata.category)
        assertEquals(2, algorithm.metadata.frameRequirements.minFrames)
        assertNull(algorithm.metadata.frameRequirements.maxFrames)
        assertEquals(InputFrameType.EXPOSURE_BRACKET, algorithm.metadata.frameRequirements.inputFrameType)
    }

    @Test
    fun denoisersExposeSingleFrameDenoiseMetadata() {
        val variants = mapOf(
            "GuidedFilter" to GuidedFilterDenoise(),
            "GuidedFilterColor" to GuidedFilterColorDenoise(),
            "FastBilateral" to FastBilateralDenoise(),
        )

        for ((expectedName, algorithm) in variants) {
            assertEquals(expectedName, algorithm.name)
            assertEquals(AlgorithmCategory.DENOISE, algorithm.metadata.category)
            assertEquals(1, algorithm.metadata.frameRequirements.minFrames)
            assertEquals(1, algorithm.metadata.frameRequirements.maxFrames)
            assertEquals(InputFrameType.SINGLE, algorithm.metadata.frameRequirements.inputFrameType)
        }
    }

    @Test
    fun sharpenersExposeSingleFrameSharpeningMetadata() {
        val variants = mapOf(
            "LinearUnsharpMask" to LinearUnsharpMasking(),
            "AdaptiveUnsharpMask" to AdaptiveUnsharpMasking(),
        )

        for ((expectedName, algorithm) in variants) {
            assertEquals(expectedName, algorithm.name)
            assertEquals(AlgorithmCategory.SHARPENING, algorithm.metadata.category)
            assertEquals(1, algorithm.metadata.frameRequirements.minFrames)
            assertEquals(1, algorithm.metadata.frameRequirements.maxFrames)
            assertEquals(InputFrameType.SINGLE, algorithm.metadata.frameRequirements.inputFrameType)
        }
    }

    @Test
    fun registryResolvesAlgorithmsByMetadataName() {
        val registry = AlgorithmRegistry()
        val algorithm = registry.getByName("ExposureFusion")

        assertEquals(ExposureFusion::class, algorithm::class)
        assertEquals("ExposureFusion", algorithm.metadata.name)
    }

    @Test
    fun registryNamesAreUniqueAndResolvable() {
        val registry = AlgorithmRegistry()
        val names = registry.getAll().map { it.name }

        assertEquals(names.size, names.distinct().size, "duplicate algorithm name in $names")
        assertEquals(names.size, names.map { it.lowercase() }.distinct().size, "id collision in $names")
        for (name in names) {
            assertEquals(name, registry.getByName(name).name)
        }
    }

    @Test
    fun declaredParameterIdsAreUniquePerAlgorithm() {
        for (algorithm in AlgorithmRegistry().getAll()) {
            val ids = algorithm.metadata.parameters.map { it.id }
            assertEquals(ids.size, ids.distinct().size, "duplicate parameter id in ${algorithm.name}: $ids")
        }
    }

    /**
     * Guards the one failure mode of declaring ranges separately from the constructors they feed:
     * cross-parameter constraints such as `alphaDh > alphaDl` are checked at construction, so a
     * range that drifts out of bounds would otherwise only surface as a failed run on device.
     */
    @Test
    fun everyDeclaredParameterExtremeConstructs() {
        for (algorithm in AlgorithmRegistry().getAll()) {
            val parameters = algorithm.metadata.parameters
            if (parameters.isEmpty()) continue

            val extremes = mapOf(
                "min" to parameters.associate { it.id to it.min },
                "max" to parameters.associate { it.id to it.max },
                "default" to parameters.associate { it.id to it.default },
            )
            for ((label, values) in extremes) {
                val configured = algorithm.withParameters(values)
                assertEquals(
                    algorithm.name,
                    configured.name,
                    "${algorithm.name} changed identity at $label parameters",
                )
            }
        }
    }

    @Test
    fun withParametersIgnoresUnknownAndOutOfRangeValues() {
        for (algorithm in AlgorithmRegistry().getAll()) {
            val parameters = algorithm.metadata.parameters
            if (parameters.isEmpty()) continue

            val hostile = parameters.associate { it.id to Double.NaN } +
                parameters.associate { "${it.id}_removed" to 1.0 }
            assertEquals(algorithm.name, algorithm.withParameters(hostile).name)
            assertEquals(
                algorithm.name,
                algorithm.withParameters(parameters.associate { it.id to -1e9 }).name,
            )
            assertEquals(
                algorithm.name,
                algorithm.withParameters(parameters.associate { it.id to 1e9 }).name,
            )
        }
    }
}
