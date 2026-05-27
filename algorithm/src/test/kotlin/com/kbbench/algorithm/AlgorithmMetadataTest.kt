package com.kbbench.algorithm

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
    fun registryResolvesAlgorithmsByMetadataName() {
        val registry = AlgorithmRegistry()
        val algorithm = registry.getByName("ExposureFusion")

        assertEquals(ExposureFusion::class, algorithm::class)
        assertEquals("ExposureFusion", algorithm.metadata.name)
    }
}

