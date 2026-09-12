package com.kbbench.app.preprocessing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kbbench.algorithm.preprocessing.ArgbTransfer
import com.kbbench.algorithm.preprocessing.TransferCurve
import com.kbbench.algorithm.preprocessing.TransferEncoding
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArgbPngTest {
    @Test
    fun previewFailureRemovesIncompletePair() {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val canonical = File.createTempFile("canonical", ".png", directory)
        val preview = File(canonical, "preview.png")
        val bitmap = ArgbPng.bitmap(intArrayOf(0xFF123456.toInt()), 1, 1)
        try {
            try {
                ArgbPng.save(canonical, preview, bitmap, ArgbTransfer.toSrgb(TransferCurve()))
                fail("Expected preview write to fail")
            } catch (_: IOException) {
                assertFalse(canonical.exists())
                assertFalse(preview.exists())
            }
        } finally {
            bitmap.recycle()
            canonical.delete()
        }
    }

    @Test
    fun canonicalPixelsSurvivePngWhilePreviewUsesDeclaredTransfer() {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val canonical = File.createTempFile("canonical", ".png", directory)
        val preview = File.createTempFile("preview", ".png", directory)
        val pixels = intArrayOf(0x00010203, 0x014080FF, 0x7F4080FF, 0xFF123456.toInt())
        try {
            for (encoding in TransferEncoding.entries) {
                val transfer = ArgbTransfer.toSrgb(TransferCurve(encoding))
                val bitmap = ArgbPng.bitmap(pixels, 2, 2)
                try { ArgbPng.save(canonical, preview, bitmap, transfer) } finally { bitmap.recycle() }
                assertArrayEquals(pixels, ArgbPng.decode(canonical))
                assertArrayEquals(transfer.apply(pixels), ArgbPng.decode(preview))
            }
        } finally {
            canonical.delete()
            preview.delete()
        }
    }
}
