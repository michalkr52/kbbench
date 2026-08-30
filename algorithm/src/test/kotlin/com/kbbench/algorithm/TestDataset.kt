package com.kbbench.algorithm

import java.io.File
import javax.imageio.ImageIO

/**
 * Optional image sets that give the tests real ground truth, loaded from `src/test/resources`.
 *
 * Two shapes, because the two algorithm families need different things:
 *
 * - `clean/` holds sharp, low-noise reference images (Set14, Kodak24, CBSD68 and the like). Tests
 *   degrade them in code and score the recovery, so the truth is genuinely clean — unlike
 *   `input.png`, which is a real photo and carries sensor noise a restoration score would then
 *   reward an algorithm for reproducing.
 * - `pairs/clean/` and `pairs/noisy/` hold real noisy/clean captures matched by filename (SIDD,
 *   PolyU, RENOIR). Here the degradation is a real sensor's, not a model of one, which is the
 *   strongest evidence available for a denoiser.
 *
 * Both are absent by default and every test that needs them skips rather than passes, so a checkout
 * without the data still reports honestly. `pairs/` is gitignored: SIDD and friends are licensed for
 * research use without redistribution.
 *
 * Frames are centre-cropped to [MAX_FRAME] and never downscaled — resampling averages noise away and
 * would quietly flatter every denoiser.
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

/** Cap on how many frames a single test walks, so the suite stays quick. */
internal const val MAX_FRAMES = 8

/** Clean references for degrade-and-restore tests, empty when `clean/` is missing. */
internal fun loadCleanReferences(): List<TestFrame> =
    imageFilesIn(File(TEST_RESOURCES, "clean")).map { load(it, it.nameWithoutExtension) }

/**
 * Real noisy/clean captures, empty when `pairs/` is missing.
 *
 * A clean frame with no identically named partner under `pairs/noisy/` is an error rather than a
 * silent omission — a half-copied dataset should not look like a small one.
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
