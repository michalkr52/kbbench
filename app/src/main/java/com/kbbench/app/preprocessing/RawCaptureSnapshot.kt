package com.kbbench.app.preprocessing

import com.kbbench.algorithm.preprocessing.ShadingMap
import org.json.JSONArray
import org.json.JSONObject

data class RawCropSnapshot(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(left >= 0 && top >= 0 && width > 0 && height > 0) { "Invalid RAW crop" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("width", width)
        .put("height", height)

    companion object {
        fun fromJson(json: JSONObject): RawCropSnapshot = RawCropSnapshot(
            left = json.getInt("left"),
            top = json.getInt("top"),
            width = json.getInt("width"),
            height = json.getInt("height"),
        )
    }
}

data class ShadingMapSnapshot(
    val columns: Int,
    val rows: Int,
    val gains: List<Float>,
) {
    init {
        require(columns >= 2 && rows >= 2) { "RAW shading map must be at least 2x2" }
        require(gains.size >= columns * rows * 4 && gains.all { it.isFinite() && it > 0f }) {
            "Invalid RAW shading map gains"
        }
    }

    fun toShadingMap(): ShadingMap = ShadingMap(gains.toFloatArray(), columns, rows)

    fun toJson(): JSONObject = JSONObject()
        .put("columns", columns)
        .put("rows", rows)
        .put("gains", JSONArray(gains))

    companion object {
        fun from(map: ShadingMap): ShadingMapSnapshot = ShadingMapSnapshot(
            columns = map.columns,
            rows = map.rows,
            gains = map.gains.toList(),
        )

        fun fromJson(json: JSONObject): ShadingMapSnapshot = ShadingMapSnapshot(
            columns = json.getInt("columns"),
            rows = json.getInt("rows"),
            gains = json.getJSONArray("gains").toFloatList(),
        )
    }
}

/** Immutable capture-side RAW decisions retained for later source reprocessing. */
data class RawCaptureSnapshot(
    val rawWidth: Int,
    val rawHeight: Int,
    val whiteLevel: Int,
    val blackLevel: Int,
    val cfaPattern: Int,
    val metadataWhiteBalanceGains: List<Float>?,
    val colorMatrix: List<Float>?,
    val shadingMap: ShadingMapSnapshot?,
    val crop: RawCropSnapshot?,
    val orientationDegrees: Int,
) {
    init {
        require(rawWidth > 0 && rawHeight > 0) { "Invalid RAW dimensions" }
        require(blackLevel >= 0 && whiteLevel > blackLevel) { "Invalid RAW levels" }
        require(metadataWhiteBalanceGains == null ||
            metadataWhiteBalanceGains.size == 3 && metadataWhiteBalanceGains.all { it.isFinite() && it > 0f }) {
            "Invalid RAW white-balance gains"
        }
        require(colorMatrix == null || colorMatrix.size == 9 && colorMatrix.all { it.isFinite() }) {
            "Invalid RAW color matrix"
        }
        require(orientationDegrees in setOf(0, 90, 180, 270)) { "Invalid RAW orientation" }
        require(crop == null || crop.left + crop.width <= rawWidth && crop.top + crop.height <= rawHeight) {
            "RAW crop is outside the source dimensions"
        }
    }

    fun toJson(): JSONObject = JSONObject()
            .put("raw_width", rawWidth)
            .put("raw_height", rawHeight)
            .put("white_level", whiteLevel)
            .put("black_level", blackLevel)
            .put("cfa_pattern", cfaPattern)
            .put("metadata_white_balance_gains", metadataWhiteBalanceGains?.let(::JSONArray) ?: JSONObject.NULL)
            .put("color_matrix", colorMatrix?.let(::JSONArray) ?: JSONObject.NULL)
            .put("shading_map", shadingMap?.toJson() ?: JSONObject.NULL)
            .put("crop", crop?.toJson() ?: JSONObject.NULL)
            .put("orientation_degrees", orientationDegrees)

    companion object {
        fun fromJson(json: JSONObject): RawCaptureSnapshot {
            return RawCaptureSnapshot(
                rawWidth = json.getInt("raw_width"),
                rawHeight = json.getInt("raw_height"),
                whiteLevel = json.getInt("white_level"),
                blackLevel = json.getInt("black_level"),
                cfaPattern = json.getInt("cfa_pattern"),
                metadataWhiteBalanceGains = json.optionalFloatList("metadata_white_balance_gains"),
                colorMatrix = json.optionalFloatList("color_matrix"),
                shadingMap = json.optJSONObject("shading_map")?.let(ShadingMapSnapshot::fromJson),
                crop = json.optJSONObject("crop")?.let(RawCropSnapshot::fromJson),
                orientationDegrees = json.getInt("orientation_degrees"),
            )
        }
    }
}

private fun JSONObject.optionalFloatList(key: String): List<Float>? =
    if (isNull(key)) null else getJSONArray(key).toFloatList()

private fun JSONArray.toFloatList(): List<Float> = List(length()) { index -> getDouble(index).toFloat() }
