package com.kbbench.algorithm.base

import kotlin.math.log10

data class QualityMetrics(
    val psnr: Double,
    val ssim: Double,
)

fun calculateQualityMetrics(referencePixels: IntArray, candidatePixels: IntArray): QualityMetrics {
    require(referencePixels.size == candidatePixels.size) {
        "Reference and candidate images must have the same number of pixels"
    }

    var squaredErrorSum = 0.0
    var refLumaSum = 0.0
    var candLumaSum = 0.0
    var refLumaSqSum = 0.0
    var candLumaSqSum = 0.0
    var crossLumaSum = 0.0

    for (i in referencePixels.indices) {
        val ref = referencePixels[i]
        val cand = candidatePixels[i]

        val refR = (ref shr 16) and 0xFF
        val refG = (ref shr 8) and 0xFF
        val refB = ref and 0xFF
        val candR = (cand shr 16) and 0xFF
        val candG = (cand shr 8) and 0xFF
        val candB = cand and 0xFF

        val dr = refR - candR
        val dg = refG - candG
        val db = refB - candB
        squaredErrorSum += (dr * dr + dg * dg + db * db).toDouble()

        val refLuma = 0.299 * refR + 0.587 * refG + 0.114 * refB
        val candLuma = 0.299 * candR + 0.587 * candG + 0.114 * candB
        refLumaSum += refLuma
        candLumaSum += candLuma
        refLumaSqSum += refLuma * refLuma
        candLumaSqSum += candLuma * candLuma
        crossLumaSum += refLuma * candLuma
    }

    val pixelCount = referencePixels.size.toDouble()
    val mse = squaredErrorSum / (pixelCount * 3.0)
    val psnr = if (mse == 0.0) {
        Double.POSITIVE_INFINITY
    } else {
        10.0 * log10((255.0 * 255.0) / mse)
    }

    val refMean = refLumaSum / pixelCount
    val candMean = candLumaSum / pixelCount
    val refVariance = (refLumaSqSum / pixelCount) - (refMean * refMean)
    val candVariance = (candLumaSqSum / pixelCount) - (candMean * candMean)
    val covariance = (crossLumaSum / pixelCount) - (refMean * candMean)

    val c1 = (0.01 * 255.0) * (0.01 * 255.0)
    val c2 = (0.03 * 255.0) * (0.03 * 255.0)
    val ssimNumerator = (2.0 * refMean * candMean + c1) * (2.0 * covariance + c2)
    val ssimDenominator =
        (refMean * refMean + candMean * candMean + c1) *
            (refVariance.coerceAtLeast(0.0) + candVariance.coerceAtLeast(0.0) + c2)
    val ssim = if (ssimDenominator == 0.0) {
        1.0
    } else {
        (ssimNumerator / ssimDenominator).coerceIn(-1.0, 1.0)
    }

    return QualityMetrics(
        psnr = psnr,
        ssim = ssim,
    )
}
