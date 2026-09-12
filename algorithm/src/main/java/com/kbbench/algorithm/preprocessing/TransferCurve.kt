package com.kbbench.algorithm.preprocessing

import kotlin.math.expm1
import kotlin.math.ln1p
import kotlin.math.pow

enum class TransferEncoding {
    SRGB,
    LINEAR,
    LOG,
}

data class TransferCurve(
    val encoding: TransferEncoding = TransferEncoding.SRGB,
    val logStrength: Double = 9.0,
) {
    init {
        require(logStrength.isFinite() && logStrength > 0.0) {
            "Log strength must be finite and positive"
        }
    }

    private val logDenominator = ln1p(logStrength)
    private val logLut: IntArray by lazy {
        IntArray(LUT_SIZE) { index ->
            quantize(encode(index.toDouble() / (LUT_SIZE - 1)))
        }
    }

    fun encode(linear: Double): Double {
        require(linear.isFinite() && linear in 0.0..1.0) { "Expected a bounded linear value" }
        return when (encoding) {
            TransferEncoding.LINEAR -> linear
            TransferEncoding.SRGB -> if (linear <= 0.0031308) linear * 12.92
                else 1.055 * linear.pow(1.0 / 2.4) - 0.055
            TransferEncoding.LOG -> ln1p(logStrength * linear) / logDenominator
        }
    }

    fun decode(encoded: Double): Double {
        require(encoded.isFinite() && encoded in 0.0..1.0) { "Expected a bounded encoded value" }
        return when (encoding) {
            TransferEncoding.LINEAR -> encoded
            TransferEncoding.SRGB -> if (encoded <= 0.04045) encoded / 12.92
                else ((encoded + 0.055) / 1.055).pow(2.4)
            TransferEncoding.LOG -> expm1(encoded * logDenominator) / logStrength
        }
    }

    fun encode8(linear: Float): Int {
        require(linear.isFinite()) { "Cannot encode a non-finite value" }
        val bounded = linear.coerceIn(0f, 1f)
        return when (encoding) {
            TransferEncoding.SRGB -> ColorTransform.encodeSrgb8(bounded)
            TransferEncoding.LINEAR -> quantize(bounded.toDouble())
            TransferEncoding.LOG -> logLut[(bounded * (LUT_SIZE - 1)).toInt()]
        }
    }

    private fun quantize(value: Double): Int = (value * 255.0 + 0.5).toInt().coerceIn(0, 255)

    companion object {
        const val VERSION = 1
        private const val LUT_SIZE = 65536
    }
}
