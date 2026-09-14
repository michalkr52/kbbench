package com.kbbench.algorithm.preprocessing

import java.nio.ByteBuffer

enum class WhiteBalanceSource {
    METADATA,
    METADATA_MISSING,
    IDENTITY,
    MANUAL,
}

enum class AppliedLensShading {
    OFF,
    UNAVAILABLE,
    SENSOR_MAP,
    DNG_RECONSTRUCTED_MAP,
    DNG_GAIN_MAPS,
}

data class ResolvedRawPreprocessing(
    val requested: PreprocessingConfig,
    val whiteBalanceGains: WhiteBalanceGains,
    val whiteBalanceSource: WhiteBalanceSource,
    val lensShading: AppliedLensShading,
    val suppliedGainMapCount: Int,
    val appliedGainMapCount: Int,
)

data class RawPreprocessingResult(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val settings: ResolvedRawPreprocessing,
)

object RawPreprocessor {
    const val VERSION = 1

    fun process(
        rawBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        whiteLevel: Int,
        blackLevel: Int,
        pattern: CfaPattern,
        whiteBalanceGains: WhiteBalanceGains? = null,
        colorMatrix: FloatArray? = null,
        shadingMap: ShadingMap? = null,
        gainMaps: List<GainMap> = emptyList(),
        config: PreprocessingConfig = PreprocessingConfig(),
    ): RawPreprocessingResult {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE) {
            "Invalid RAW dimensions"
        }
        require(pixelStride >= 2 && rowStride.toLong() >= (width - 1L) * pixelStride + 2) {
            "Invalid RAW16 strides"
        }
        val requiredBytes = (height - 1L) * rowStride + (width - 1L) * pixelStride + 2
        require(requiredBytes <= rawBuffer.limit()) { "RAW16 buffer is too small" }
        require(blackLevel in 0..65534 && whiteLevel > blackLevel && whiteLevel <= 65535) {
            "Invalid RAW black/white levels"
        }
        require(colorMatrix == null || (colorMatrix.size == 9 && colorMatrix.all { it.isFinite() })) {
            "Color matrix must contain nine finite values"
        }
        require(shadingMap == null || gainMaps.isEmpty()) { "Provide only one source of lens shading" }

        val raw = Raw16Decoder.normalizeRaw16(
            rawBuffer, width, height, rowStride, pixelStride, whiteLevel, blackLevel,
        )
        var appliedGainMapCount = 0
        val effectiveShading = when {
            config.lensShading == LensShadingMode.OFF -> AppliedLensShading.OFF
            shadingMap != null -> {
                LensShadingCorrection.applyInPlace(raw, width, height, pattern, shadingMap)
                AppliedLensShading.SENSOR_MAP
            }
            gainMaps.isNotEmpty() -> {
                val reconstructed = GainMapCorrection.toShadingMap(gainMaps, pattern)
                if (reconstructed != null) {
                    LensShadingCorrection.applyInPlace(raw, width, height, pattern, reconstructed)
                    appliedGainMapCount = gainMaps.size
                    AppliedLensShading.DNG_RECONSTRUCTED_MAP
                } else {
                    appliedGainMapCount = GainMapCorrection.applyAndCountInPlace(raw, width, height, gainMaps)
                    if (appliedGainMapCount == 0) AppliedLensShading.UNAVAILABLE
                    else AppliedLensShading.DNG_GAIN_MAPS
                }
            }
            else -> AppliedLensShading.UNAVAILABLE
        }
        val effectiveGains = config.resolveWhiteBalance(whiteBalanceGains)
        val source = when (config.whiteBalance) {
            WhiteBalanceMode.METADATA -> if (whiteBalanceGains == null) {
                WhiteBalanceSource.METADATA_MISSING
            } else WhiteBalanceSource.METADATA
            WhiteBalanceMode.IDENTITY -> WhiteBalanceSource.IDENTITY
            WhiteBalanceMode.MANUAL -> WhiteBalanceSource.MANUAL
        }
        val pixels = BayerDemosaic.demosaic(
            raw, width, height, pattern,
            rGain = effectiveGains.red,
            gGain = effectiveGains.green,
            bGain = effectiveGains.blue,
            colorMatrix = colorMatrix,
            config = config,
        )
        return RawPreprocessingResult(
            pixels, width, height,
            ResolvedRawPreprocessing(
                config, effectiveGains, source, effectiveShading, gainMaps.size, appliedGainMapCount,
            ),
        )
    }
}
