package com.kbbench.algorithm

import java.io.File
import javax.imageio.ImageIO

/**
 * Optional image sets giving the tests real ground truth, loaded from `src/test/resources`:
 *
 * - `clean/` — sharp, low-noise references (Set14, Kodak24, CBSD68). Tests degrade these in code and
 *   score the recovery.
 * - `pairs/clean/` and `pairs/noisy/` — real noisy/clean captures matched by filename (SIDD, PolyU,
 *   RENOIR), where the degradation is a real sensor's rather than a model of one.
 *
 * Both are gitignored, so tests that need them skip rather than pass. See the resources README.
 *
 * Frames are centre-cropped to [MAX_FRAME] and never downscaled: resampling averages noise away and
 * would flatter every denoiser.
 */

internal data class TestFrame(
    val name: String,
    val pixels: IntArray,
    val width: Int,
    val height: Int,
)

internal data class TestPair(val name: String, val clean: TestFrame, val noisy: TestFrame)

/** Longest side kept from a dataset frame; SIDD captures are ~5000 px and far too slow whole. */
internal const val MAX_FRAME = 512

/**
 * Cap on how many frames a single test walks, so dropping in a full dataset cannot hang the suite.
 * Decoding the multi-megabyte source PNGs dominates the runtime, not the algorithms.
 */
internal const val MAX_FRAMES = 24

/**
 * Capture settings SIDD encodes in its directory names, as in `0050_002_N6_03200_03200_5500_L`.
 * Other datasets name their files differently, so this is best-effort.
 */
internal data class SiddMetadata(
    val camera: String,
    val iso: Int,
    val shutter: Int,
    val illuminantK: Int,
    /** `N` for normal lighting, `L` for low light. */
    val brightness: String,
)

private val SIDD_NAME = Regex("""^\d{4}_\d{3}_([A-Z0-9]+)_(\d+)_(\d+)_(\d+)_([A-Z])$""")

/** @return the settings encoded in [name], or `null` if it is not a SIDD scene name. */
internal fun parseSiddName(name: String): SiddMetadata? {
    val m = SIDD_NAME.matchEntire(name) ?: return null
    return SiddMetadata(
        camera = m.groupValues[1],
        iso = m.groupValues[2].toInt(),
        shutter = m.groupValues[3].toInt(),
        illuminantK = m.groupValues[4].toInt(),
        brightness = m.groupValues[5],
    )
}

/**
 * Loaded once per JVM: decoding the multi-megabyte dataset PNGs costs more than everything the
 * tests then do with them. Safe to share because no algorithm writes into its input frame.
 */
internal val cleanReferences: List<TestFrame> by lazy { loadCleanReferences() }
internal val noisyPairs: List<TestPair> by lazy { loadNoisyPairs() }

/** Clean references for degrade-and-restore tests, empty when `clean/` is missing. */
internal fun loadCleanReferences(): List<TestFrame> =
    imageFilesIn(File(TEST_RESOURCES, "clean")).map { load(it, it.nameWithoutExtension) }

/**
 * @return real noisy/clean captures, empty when `pairs/` is missing.
 * @throws IllegalArgumentException if a `pairs/clean/` frame has no identically named partner under
 *   `pairs/noisy/`, or the two differ in size — a half-copied dataset must not look like a small one.
 */
internal fun loadNoisyPairs(): List<TestPair> {
    val cleanDir = File(TEST_RESOURCES, "pairs/clean")
    val noisyDir = File(TEST_RESOURCES, "pairs/noisy")

    return imageFilesIn(cleanDir).map { cleanFile ->
        val noisyFile = File(noisyDir, cleanFile.name)
        require(noisyFile.isFile) {
            "Brak pary dla ${cleanFile.name}: oczekiwano ${noisyFile.path}"
        }

        val name = cleanFile.nameWithoutExtension
        val clean = load(cleanFile, name)
        val noisy = load(noisyFile, name)
        require(clean.width == noisy.width && clean.height == noisy.height) {
            "Para $name ma rozne wymiary: ${clean.width}x${clean.height} vs ${noisy.width}x${noisy.height}"
        }

        TestPair(name, clean, noisy)
    }
}

private val TEST_RESOURCES = File("src/test/resources")

private fun imageFilesIn(dir: File): List<File> =
    if (!dir.isDirectory) {
        emptyList()
    } else {
        dir.listFiles { file -> file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS }
            .orEmpty()
            .sortedBy { it.name }
            .take(MAX_FRAMES)
    }

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp")

private fun load(file: File, name: String): TestFrame {
    val image = ImageIO.read(file) ?: error("Nie udalo sie odczytac obrazu: ${file.path}")

    // Both frames of a pair must be cropped identically, which centring guarantees for equal sizes.
    val width = minOf(image.width, MAX_FRAME)
    val height = minOf(image.height, MAX_FRAME)
    val x0 = (image.width - width) / 2
    val y0 = (image.height - height) / 2

    val pixels = IntArray(width * height)
    image.getRGB(x0, y0, width, height, pixels, 0, width)

    return TestFrame(name = name, pixels = pixels, width = width, height = height)
}
