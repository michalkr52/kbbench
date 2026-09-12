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

    /**
     * Index of the CFA channel sitting at position ([x], [y]) of the 2x2 pattern, in Android's
     * `LensShadingMap` order: 0 red, 1 green sharing the red row, 2 green sharing the blue row,
     * 3 blue.
     */
    fun channelAt(x: Int, y: Int): Int {
        val px = x and 1
        val py = y and 1
        val rX: Int; val rY: Int
        val bX: Int; val bY: Int
        when (this) {
            RGGB -> { rX = 0; rY = 0; bX = 1; bY = 1 }
            GRBG -> { rX = 1; rY = 0; bX = 0; bY = 1 }
            GBRG -> { rX = 0; rY = 1; bX = 1; bY = 0 }
            BGGR -> { rX = 1; rY = 1; bX = 0; bY = 0 }
        }
        return when {
            px == rX && py == rY -> 0
            px == bX && py == bY -> 3
            py == rY -> 1
            else -> 2
        }
    }

    companion object {
        fun fromId(id: Int): CfaPattern = entries.firstOrNull { it.id == id } ?: RGGB
    }
}
