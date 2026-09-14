package com.kbbench.app.preprocessing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kbbench.algorithm.preprocessing.ArgbTransfer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object ArgbPng {
    fun bitmap(pixels: IntArray, width: Int, height: Int): Bitmap {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong()) {
            "Pixel buffer size does not match image dimensions"
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPremultiplied(false)
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    fun decode(file: File): IntArray {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("Missing or invalid canonical image: ${file.name}")
        return try {
            IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        } finally { bitmap.recycle() }
    }

    fun save(canonical: File, display: File, bitmap: Bitmap, transfer: ArgbTransfer) {
        try {
            write(canonical, bitmap)
            if (display == canonical) return
            val row = IntArray(bitmap.width)
            for (rowIndex in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, rowIndex, bitmap.width, 1)
                for (column in row.indices) row[column] = transfer.apply(row[column])
                bitmap.setPixels(row, 0, bitmap.width, 0, rowIndex, bitmap.width, 1)
            }
            write(display, bitmap)
        } catch (failure: Exception) {
            canonical.delete()
            if (display != canonical) display.delete()
            throw failure
        }
    }

    private fun write(file: File, bitmap: Bitmap) {
        FileOutputStream(file).use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Failed to save ${file.name}" }
        }
    }
}
