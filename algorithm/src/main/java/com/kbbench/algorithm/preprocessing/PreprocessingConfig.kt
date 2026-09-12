package com.kbbench.algorithm.preprocessing

import kotlin.math.pow

enum class WhiteBalanceMode {
    METADATA,
    IDENTITY,
    MANUAL,
}

enum class LensShadingMode {
    METADATA,
    OFF,
}

enum class HighlightMode {
    NEUTRALIZE_CLIPPED,
    CLIP_CHANNELS,
}

data class WhiteBalanceGains(
    val red: Float = 1f,
    val green: Float = 1f,
    val blue: Float = 1f,
) {
    init {
        require(red.isFinite() && red > 0f && green.isFinite() && green > 0f &&
            blue.isFinite() && blue > 0f) { "White balance gains must be finite and positive" }
        require((red / green).isFinite() && red / green > 0f &&
            (blue / green).isFinite() && blue / green > 0f) { "Invalid relative white balance gains" }
    }

    fun relativeToGreen(): WhiteBalanceGains = WhiteBalanceGains(red / green, 1f, blue / green)
}

data class PreprocessingConfig(
    val transferCurve: TransferCurve = TransferCurve(),
    val exposureOffsetEv: Double = 0.0,
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.METADATA,
    val manualWhiteBalance: WhiteBalanceGains = WhiteBalanceGains(),
    val lensShading: LensShadingMode = LensShadingMode.METADATA,
    val highlights: HighlightMode = HighlightMode.NEUTRALIZE_CLIPPED,
) {
    init {
        require(exposureOffsetEv.isFinite() && exposureOffsetEv in -4.0..4.0) {
            "Exposure offset must be between -4 and +4 EV"
        }
    }

    val exposureMultiplier: Float = 2.0.pow(exposureOffsetEv).toFloat()

    fun resolveWhiteBalance(metadata: WhiteBalanceGains?): WhiteBalanceGains = when (whiteBalance) {
        WhiteBalanceMode.METADATA -> metadata ?: WhiteBalanceGains()
        WhiteBalanceMode.IDENTITY -> WhiteBalanceGains()
        WhiteBalanceMode.MANUAL -> manualWhiteBalance
    }.relativeToGreen()

    companion object {
        const val VERSION = 1
    }
}
