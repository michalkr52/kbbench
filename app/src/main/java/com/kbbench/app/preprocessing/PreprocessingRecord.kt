package com.kbbench.app.preprocessing

import com.kbbench.algorithm.preprocessing.AppliedLensShading
import com.kbbench.algorithm.preprocessing.ArgbTransfer
import com.kbbench.algorithm.preprocessing.PreprocessingConfig
import com.kbbench.algorithm.preprocessing.RawPreprocessor
import com.kbbench.algorithm.preprocessing.ResolvedRawPreprocessing
import com.kbbench.algorithm.preprocessing.TransferEncoding
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

fun PreprocessingConfig.summary(): String {
    val curve = when (transferCurve.encoding) {
        TransferEncoding.SRGB -> "sRGB"
        TransferEncoding.LINEAR -> "Linear"
        TransferEncoding.LOG -> "Log ${"%.1f".format(Locale.US, transferCurve.logStrength)}"
    }
    return "$curve / ${"%+.2f".format(Locale.US, exposureOffsetEv)} EV / 8-bit"
}

enum class PreprocessingSource {
    CAMERA_RAW,
    IMPORTED_DNG,
    RENDERED_IMAGE,
    PLATFORM_DNG,
}

data class PreprocessingRecord(
    val config: PreprocessingConfig = PreprocessingConfig(),
    val source: PreprocessingSource = PreprocessingSource.RENDERED_IMAGE,
    val rawSettings: List<ResolvedRawPreprocessing> = emptyList(),
    val colorMatrix: List<Float>? = null,
) {
    val isRaw: Boolean get() = source == PreprocessingSource.CAMERA_RAW || source == PreprocessingSource.IMPORTED_DNG

    fun toJson(): JSONObject {
        val frames = JSONArray()
        rawSettings.forEachIndexed { index, settings ->
            frames.put(JSONObject()
                .put("frame_index", index)
                .put("white_balance_source", settings.whiteBalanceSource.name)
                .put("white_balance_gains", JSONArray(listOf(
                    settings.whiteBalanceGains.red, settings.whiteBalanceGains.green, settings.whiteBalanceGains.blue,
                )))
                .put("lens_shading", settings.lensShading.name)
                .put("supplied_gain_maps", settings.suppliedGainMapCount)
                .put("applied_gain_maps", settings.appliedGainMapCount)
                .put("stage_order", JSONArray(rawStageOrder(settings))))
        }
        return JSONObject()
            .put("pixel_format", "ARGB_8888")
            .put("channel_order", "ARGB")
            .put("profile", PreprocessingProfileJson.encode(config))
            .put("source", source.name)
            .put("signal_origin", if (isRaw) "sensor_derived" else "rendered_derived")
            .put("primaries", if (isRaw && colorMatrix == null) "uncalibrated_camera_rgb" else "srgb")
            .put("sample_range", JSONArray(listOf(0, 255)))
            .put("processor_version", if (isRaw) RawPreprocessor.VERSION else ArgbTransfer.VERSION)
            .put("raw_controls_available", isRaw)
            .put("effective_raw_frames", frames)
            .put("color_matrix", colorMatrix?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put("color_matrix_row_normalized", isRaw && colorMatrix != null)
            .put("rendered_stage_order", if (isRaw) JSONObject.NULL else JSONArray(if (
                config.transferCurve.encoding == TransferEncoding.SRGB && config.exposureOffsetEv == 0.0
            ) {
                listOf("decode_srgb")
            } else listOf("decode_srgb", "inverse_srgb", "software_exposure", "clip", "transfer", "quantize_8bit")))
            .put("display", JSONObject()
                .put("version", ArgbTransfer.VERSION)
                .put("transfer", "SRGB")
                .put("rule", "inverse_declared_transfer_then_clip_then_srgb")
                .put("auto_contrast", false)
                .put("camera_rgb_fallback", isRaw && colorMatrix == null))
    }

    private fun rawStageOrder(settings: ResolvedRawPreprocessing): List<String> = buildList {
        add("normalize_clip")
        if (settings.lensShading != AppliedLensShading.OFF && settings.lensShading != AppliedLensShading.UNAVAILABLE) {
            add("lens_shading_clip")
        }
        add("bilinear_demosaic")
        add("white_balance")
        add("highlight_policy")
        if (colorMatrix != null) add("color_matrix")
        addAll(listOf("software_exposure", "clip", "transfer", "quantize_8bit"))
    }
}
