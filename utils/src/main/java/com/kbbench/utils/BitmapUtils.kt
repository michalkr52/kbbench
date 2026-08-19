package com.kbbench.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri

/** Decodes a [Bitmap] from a content/file [Uri], e.g. one returned by a photo picker. */
fun decodeBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream)
    }
}

/**
 * Crops [src] to the aspect ratio of [targetWidth]x[targetHeight] (centered, discarding
 * excess content) and scales the result to that exact size, mirroring `ImageView`'s
 * `centerCrop` scale type. Used to reconcile a reference image with a candidate image's
 * dimensions before pixel-wise comparison.
 */
fun centerCropAndScale(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
    val srcRatio = src.width.toFloat() / src.height.toFloat()
    val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()

    val cropRect = if (srcRatio > targetRatio) {
        // Source is wider than target: crop left/right.
        val cropWidth = (src.height * targetRatio).toInt().coerceAtMost(src.width)
        val left = (src.width - cropWidth) / 2
        Rect(left, 0, left + cropWidth, src.height)
    } else {
        // Source is taller than target: crop top/bottom.
        val cropHeight = (src.width / targetRatio).toInt().coerceAtMost(src.height)
        val top = (src.height - cropHeight) / 2
        Rect(0, top, src.width, top + cropHeight)
    }

    val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    canvas.drawBitmap(src, cropRect, Rect(0, 0, targetWidth, targetHeight), null)
    return result
}
