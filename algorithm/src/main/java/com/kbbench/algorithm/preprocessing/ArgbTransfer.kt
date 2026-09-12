package com.kbbench.algorithm.preprocessing

class ArgbTransfer private constructor(private val channels: IntArray) {
    fun apply(pixel: Int): Int = (pixel and ALPHA_MASK) or
        (channels[(pixel ushr 16) and 255] shl 16) or
        (channels[(pixel ushr 8) and 255] shl 8) or channels[pixel and 255]

    fun apply(pixels: IntArray): IntArray = IntArray(pixels.size) { apply(pixels[it]) }

    companion object {
        const val VERSION = 1
        private const val ALPHA_MASK = 0xFF shl 24

        fun toSrgb(curve: TransferCurve): ArgbTransfer = ArgbTransfer(IntArray(256) { code ->
            if (curve.encoding == TransferEncoding.SRGB) code
            else ColorTransform.encodeSrgb8(curve.decode(code / 255.0).toFloat())
        })

        fun fromSrgb(config: PreprocessingConfig): ArgbTransfer {
            val srgb = TransferCurve()
            return ArgbTransfer(IntArray(256) { code ->
                if (config.transferCurve.encoding == TransferEncoding.SRGB && config.exposureOffsetEv == 0.0) {
                    code
                } else {
                    config.transferCurve.encode8(srgb.decode(code / 255.0).toFloat() * config.exposureMultiplier)
                }
            })
        }
    }
}
