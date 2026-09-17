package com.kbbench.algorithm.base

/** Signal domain represented by a frame's normalized channel samples. */
enum class FrameDomain {
    LINEAR,
    SRGB,
    LOG,
}

/** Storage used by a frame; intermediate stages may need values outside the display range. */
enum class FrameStorage {
    BOUNDED_U16,
    UNCLIPPED_FLOAT,
}
