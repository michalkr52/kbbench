package com.kbbench.algorithm

import com.kbbench.algorithm.base.Frame
import com.kbbench.algorithm.base.FrameDomain
import com.kbbench.algorithm.base.FrameStorage
import com.kbbench.algorithm.base.Denoiser
import com.kbbench.algorithm.base.DenoiserDescriptor
import com.kbbench.algorithm.base.DenoiserRunner
import com.kbbench.algorithm.preprocessing.GuidedFilterDenoiser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DenoiserTest {
    @Test
    fun guidedFilterDenoiserPreservesDomainAndKeepsUnclippedOutput() {
        val input = Frame.unclipped(
            red = FloatArray(9) { 1.5f },
            green = FloatArray(9) { 1.5f },
            blue = FloatArray(9) { 1.5f },
            width = 3,
            height = 3,
            sourceDepth = 12,
            domain = FrameDomain.LINEAR,
        )

        val result = DenoiserRunner.run(input, GuidedFilterDenoiser(radius = 1))

        assertTrue(result.output.isUnclipped)
        assertEquals(FrameDomain.LINEAR, result.output.domain)
        assertEquals(1.5f, result.output.r(4))
        assertEquals("guided_filter", result.denoiserId)
        assertEquals(1.0, result.effectiveParameters["radius"])
    }

    @Test
    fun denoiserRejectsAnIncompatibleDomainBeforeExecution() {
        val denoiser = object : Denoiser {
            override val descriptor = DenoiserDescriptor(
                id = "linear_only",
                name = "Linear only",
                acceptedDomains = setOf(FrameDomain.LINEAR),
            )

            override fun process(input: Frame): Frame = input.toUnclipped()
        }
        val input = Frame.fromArgb(IntArray(4), 2, 2, 8, FrameDomain.SRGB)

        assertFailsWith<IllegalArgumentException> {
            DenoiserRunner.run(input, denoiser)
        }
    }

    @Test
    fun denoiserRejectsAnUnexpectedOutputStorage() {
        val denoiser = object : Denoiser {
            override val descriptor = DenoiserDescriptor(
                id = "must_be_float",
                name = "Must be float",
                outputStorage = FrameStorage.UNCLIPPED_FLOAT,
            )

            override fun process(input: Frame): Frame = input
        }
        val input = Frame.fromArgb(IntArray(4), 2, 2, 8)

        assertFailsWith<IllegalArgumentException> {
            DenoiserRunner.run(input, denoiser)
        }
    }

    @Test
    fun denoiserKeepsOnlyOneExecutionOutput() {
        val input = Frame.fromArgb(IntArray(9), 3, 3, 8, FrameDomain.SRGB)
        val result = DenoiserRunner.run(input, GuidedFilterDenoiser(radius = 1))

        assertEquals(FrameStorage.UNCLIPPED_FLOAT, result.output.storage)
        assertTrue(result.elapsedMs >= 0)
    }
}