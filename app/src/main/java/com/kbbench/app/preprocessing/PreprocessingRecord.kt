package com.kbbench.app.preprocessing

import com.kbbench.algorithm.preprocessing.PreprocessingConfig
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
    val captureSnapshot: RawCaptureSnapshot? = null,
) {
    val isRaw: Boolean get() = source == PreprocessingSource.CAMERA_RAW || source == PreprocessingSource.IMPORTED_DNG

    fun toJson(): JSONObject {
        val frames = JSONArray()
        rawSettings.forEachIndexed { index, settings ->
            frames.put(JSONObject()
                .put("frame_index", index)
                .put("white_balance_source", settings.whiteBalanceSource.name.lowercase(Locale.ROOT))
                .put("white_balance_gains", JSONArray(listOf(
                    settings.whiteBalanceGains.red, settings.whiteBalanceGains.green, settings.whiteBalanceGains.blue,
                )))
                .put("lens_shading", settings.lensShading.name.lowercase(Locale.ROOT))
                .put("supplied_gain_maps", settings.suppliedGainMapCount)
                .put("applied_gain_maps", settings.appliedGainMapCount))
        }
        return JSONObject()
            .put("pixel_format", "ARGB_8888")
            .put("channel_order", "ARGB")
            .put("profile", PreprocessingProfileJson.encode(config))
            .put("source", source.name.lowercase(Locale.ROOT))
            .put("signal_origin", if (isRaw) "sensor_derived" else "rendered_derived")
            .put("primaries", if (isRaw && colorMatrix == null) "uncalibrated_camera_rgb" else "srgb")
            .put("sample_range", JSONArray(listOf(0, 255)))
            .put("raw_controls_available", isRaw)
            .put("effective_raw_frames", frames)
            .put("color_matrix", colorMatrix?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put("color_matrix_row_normalized", isRaw && colorMatrix != null)
            .put("capture_snapshot", captureSnapshot?.toJson() ?: JSONObject.NULL)
            .put("display", JSONObject()
                .put("transfer", "srgb")
                .put("rule", "inverse_declared_transfer_then_clip_then_srgb")
                .put("auto_contrast", false)
                .put("camera_rgb_fallback", isRaw && colorMatrix == null))
    }
}
