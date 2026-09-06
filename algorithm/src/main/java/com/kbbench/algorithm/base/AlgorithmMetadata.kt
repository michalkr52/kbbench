package com.kbbench.algorithm.base

/**
 * High-level category describing what an algorithm primarily does.
 */
enum class AlgorithmCategory {
    /** Expands or remaps tonal / contrast range of an image. */
    CONTRAST_ENHANCEMENT,

    /** Merges multiple exposures or frames to improve dynamic range. */
    HDR_FUSION,

    /** Reduces noise using multiple or single input frames. */
    DENOISE,

    /** Emphasizes edges or fine detail. */
    SHARPENING,
}

/**
 * Describes what kind of frame sequence an algorithm expects.
 */
enum class InputFrameType {
    /** Exactly one independently captured image. */
    SINGLE,

    /** Several frames captured with similar settings, typically for denoising. */
    BURST,

    /** Several frames captured with intentionally different exposures. */
    EXPOSURE_BRACKET,

    /** Multi-frame input without a stronger assumption about capture pattern. */
    GENERIC_MULTI_FRAME,
}

/**
 * Declares how many frames an algorithm accepts and what kind of capture they should represent.
 *
 * @property minFrames Minimum number of input frames required to run the algorithm.
 * @property maxFrames Optional maximum number of input frames accepted by the algorithm.
 *   `null` means there is no explicit upper bound.
 * @property inputFrameType Expected capture pattern of the provided frames.
 */
data class FrameRequirements(
    val minFrames: Int,
    val maxFrames: Int? = null,
    val inputFrameType: InputFrameType,
)

/**
 * Descriptive metadata exposed by every [ImageAlgorithm] for UI, filtering and validation.
 *
 * @property name Human-readable unique name of the algorithm.
 * @property kind Short display label describing the algorithm type, such as HDR or SR.
 * @property category Main algorithm family (for example HDR fusion or denoising).
 * @property frameRequirements Requirements describing how many frames and what kind of frames
 *   should be supplied.
 * @property description Short summary suitable for UI or logs.
 */
data class AlgorithmMetadata(
    val name: String,
    val kind: String,
    val category: AlgorithmCategory,
    val frameRequirements: FrameRequirements,
    val description: String,
)
