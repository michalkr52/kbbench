package com.kbbench.utils

import android.graphics.Bitmap

data class RgbHistogram(
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val maxCount: Int,
) {
    val isEmpty: Boolean
        get() = maxCount == 0
}

fun calculateRgbHistogram(pixels: IntArray): RgbHistogram {
    val red = IntArray(256)
    val green = IntArray(256)
    val blue = IntArray(256)

    pixels.forEach { pixel ->
        red[(pixel shr 16) and 0xff]++
        green[(pixel shr 8) and 0xff]++
        blue[pixel and 0xff]++
    }

    val maxCount = sequenceOf(red.maxOrNull(), green.maxOrNull(), blue.maxOrNull())
        .filterNotNull()
        .maxOrNull()
        ?: 0

    return RgbHistogram(red, green, blue, maxCount)
}

fun calculateRgbHistogram(bitmap: Bitmap): RgbHistogram {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return calculateRgbHistogram(pixels)
}
