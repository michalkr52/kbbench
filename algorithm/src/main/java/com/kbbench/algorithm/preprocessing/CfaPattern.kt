package com.kbbench.algorithm.preprocessing

/**
 * Bayer CFA (Color Filter Array) patterns.
 * Values match Android's CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT constants.
 */
enum class CfaPattern(val id: Int) {
    RGGB(0),
    GRBG(1),
    GBRG(2),
    BGGR(3);

    companion object {
        fun fromId(id: Int): CfaPattern = entries.firstOrNull { it.id == id } ?: RGGB
    }
}
