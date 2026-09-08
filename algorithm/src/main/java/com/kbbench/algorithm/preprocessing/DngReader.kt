package com.kbbench.algorithm.preprocessing

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Raised when a file is not a DNG this reader can turn back into a sensor mosaic. */
class DngParseException(message: String) : Exception(message)

/**
 * A parsed DNG: the sensor mosaic plus the metadata needed to render it the way the original
 * capture pipeline did.
 *
 * [raw] is 16-bit little-endian samples addressable with [rowStride]/[pixelStride], matching what
 * [Raw16Decoder.normalizeRaw16] expects from a `RAW_SENSOR` plane.
 */
class DngImage(
    val raw: ByteBuffer,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val blackLevel: Int,
    val whiteLevel: Int,
    val cfaPattern: CfaPattern,
    /** Per-channel red/green/blue multipliers recovered from `AsShotNeutral`, green normalized to 1. */
    val whiteBalanceGains: FloatArray?,
    /** Row-major camera RGB to CIE XYZ (D50), the DNG `ForwardMatrix` tags. */
    val forwardMatrix1: FloatArray?,
    val forwardMatrix2: FloatArray?,
    val calibrationIlluminant1: Int,
    val calibrationIlluminant2: Int,
    val gainMaps: List<GainMap>,
    val defaultCropX: Int,
    val defaultCropY: Int,
    val defaultCropWidth: Int,
    val defaultCropHeight: Int,
    val orientationDegrees: Int,
)

/**
 * Minimal TIFF/DNG reader for uncompressed 16-bit CFA files, so a DNG re-imported from storage
 * runs through the same normalize/shade/demosaic chain as a live capture instead of the
 * platform's own RAW renderer.
 */
object DngReader {

    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC_INTERPRETATION = 262
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_ORIENTATION = 274
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_TILE_OFFSETS = 324
    private const val TAG_SUB_IFDS = 330
    private const val TAG_CFA_PATTERN = 33422
    private const val TAG_BLACK_LEVEL = 50714
    private const val TAG_WHITE_LEVEL = 50717
    private const val TAG_DEFAULT_CROP_ORIGIN = 50719
    private const val TAG_DEFAULT_CROP_SIZE = 50720
    private const val TAG_AS_SHOT_NEUTRAL = 50728
    private const val TAG_CALIBRATION_ILLUMINANT_1 = 50778
    private const val TAG_CALIBRATION_ILLUMINANT_2 = 50779
    private const val TAG_FORWARD_MATRIX_1 = 50964
    private const val TAG_FORWARD_MATRIX_2 = 50965
    private const val TAG_OPCODE_LIST_2 = 51009

    private const val PHOTOMETRIC_CFA = 32803
    private const val COMPRESSION_NONE = 1
    private const val OPCODE_GAIN_MAP = 9

    fun read(file: File): DngImage {
        val buffer = RandomAccessFile(file, "r").use { raf ->
            // The mapping outlives the channel, so slices of it stay valid after this block.
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.channel.size())
        }
        if (buffer.capacity() < 8) throw DngParseException("File is too small to be a TIFF")

        val byteOrder = when (val marker = buffer.getShort(0).toInt() and 0xFFFF) {
            0x4949 -> ByteOrder.LITTLE_ENDIAN
            0x4D4D -> ByteOrder.BIG_ENDIAN
            else -> throw DngParseException("Not a TIFF/DNG file, byte order marker $marker")
        }
        buffer.order(byteOrder)
        if (buffer.getShort(2).toInt() and 0xFFFF != 42) throw DngParseException("Bad TIFF magic")

        val ifd0 = readIfd(buffer, buffer.getInt(4))
        val candidates = mutableListOf(ifd0)
        ifd0[TAG_SUB_IFDS]?.let { entry ->
            readLongs(buffer, entry).forEach { offset -> candidates.add(readIfd(buffer, offset.toInt())) }
        }
        val rawIfd = candidates.firstOrNull {
            scalar(buffer, it[TAG_PHOTOMETRIC_INTERPRETATION])?.toInt() == PHOTOMETRIC_CFA
        } ?: throw DngParseException("No uncompressed CFA image found in the DNG")

        fun tag(id: Int) = rawIfd[id] ?: ifd0[id]

        val compression = scalar(buffer, tag(TAG_COMPRESSION))?.toInt() ?: COMPRESSION_NONE
        if (compression != COMPRESSION_NONE) {
            throw DngParseException("Compressed DNG (compression $compression) is not supported")
        }
        if (tag(TAG_TILE_OFFSETS) != null) throw DngParseException("Tiled DNG is not supported")
        val bitsPerSample = scalar(buffer, tag(TAG_BITS_PER_SAMPLE))?.toInt() ?: 16
        if (bitsPerSample != 16) throw DngParseException("Only 16-bit CFA data is supported, got $bitsPerSample")
        val samplesPerPixel = scalar(buffer, tag(TAG_SAMPLES_PER_PIXEL))?.toInt() ?: 1
        if (samplesPerPixel != 1) throw DngParseException("Expected a single CFA sample per pixel")

        val width = scalar(buffer, tag(TAG_IMAGE_WIDTH))?.toInt()
            ?: throw DngParseException("Missing ImageWidth")
        val height = scalar(buffer, tag(TAG_IMAGE_LENGTH))?.toInt()
            ?: throw DngParseException("Missing ImageLength")
        if (width <= 0 || height <= 0) throw DngParseException("Bad image size ${width}x$height")

        val stripOffsets = tag(TAG_STRIP_OFFSETS)?.let { readLongs(buffer, it) }
            ?: throw DngParseException("Missing StripOffsets")
        val stripByteCounts = tag(TAG_STRIP_BYTE_COUNTS)?.let { readLongs(buffer, it) }
            ?: throw DngParseException("Missing StripByteCounts")
        val rawPlane = assembleStrips(buffer, byteOrder, stripOffsets, stripByteCounts, width, height)

        val whiteLevel = scalar(buffer, tag(TAG_WHITE_LEVEL))?.toInt() ?: ((1 shl bitsPerSample) - 1)
        val blackLevel = tag(TAG_BLACK_LEVEL)
            ?.let { entry -> readDoubles(buffer, entry).takeIf { it.isNotEmpty() }?.average() }
            ?.toInt() ?: 0

        val cfaPattern = tag(TAG_CFA_PATTERN)
            ?.let { readLongs(buffer, it) }
            ?.takeIf { it.size >= 4 }
            ?.let { cfaPatternOf(it) }
            ?: CfaPattern.RGGB

        val cropOrigin = tag(TAG_DEFAULT_CROP_ORIGIN)?.let { readDoubles(buffer, it) }
        val cropSize = tag(TAG_DEFAULT_CROP_SIZE)?.let { readDoubles(buffer, it) }
        val cropX = cropOrigin?.getOrNull(0)?.toInt()?.coerceIn(0, width - 1) ?: 0
        val cropY = cropOrigin?.getOrNull(1)?.toInt()?.coerceIn(0, height - 1) ?: 0
        val cropWidth = cropSize?.getOrNull(0)?.toInt()?.coerceIn(1, width - cropX) ?: (width - cropX)
        val cropHeight = cropSize?.getOrNull(1)?.toInt()?.coerceIn(1, height - cropY) ?: (height - cropY)

        return DngImage(
            raw = rawPlane,
            width = width,
            height = height,
            rowStride = width * 2,
            pixelStride = 2,
            blackLevel = blackLevel,
            whiteLevel = whiteLevel,
            cfaPattern = cfaPattern,
            whiteBalanceGains = tag(TAG_AS_SHOT_NEUTRAL)?.let { gainsFromNeutral(readDoubles(buffer, it)) },
            forwardMatrix1 = tag(TAG_FORWARD_MATRIX_1)?.let { matrixOf(readDoubles(buffer, it)) },
            forwardMatrix2 = tag(TAG_FORWARD_MATRIX_2)?.let { matrixOf(readDoubles(buffer, it)) },
            calibrationIlluminant1 = scalar(buffer, tag(TAG_CALIBRATION_ILLUMINANT_1))?.toInt() ?: 0,
            calibrationIlluminant2 = scalar(buffer, tag(TAG_CALIBRATION_ILLUMINANT_2))?.toInt() ?: 0,
            gainMaps = tag(TAG_OPCODE_LIST_2)?.let { readGainMaps(buffer, it) } ?: emptyList(),
            defaultCropX = cropX,
            defaultCropY = cropY,
            defaultCropWidth = cropWidth,
            defaultCropHeight = cropHeight,
            orientationDegrees = when (scalar(buffer, tag(TAG_ORIENTATION))?.toInt() ?: 1) {
                6, 5 -> 90
                3 -> 180
                8, 7 -> 270
                else -> 0
            },
        )
    }

    /**
     * Copies the CFA strips into one contiguous little-endian plane, or slices the mapped file
     * directly when the layout already matches.
     */
    private fun assembleStrips(
        buffer: ByteBuffer,
        byteOrder: ByteOrder,
        offsets: LongArray,
        byteCounts: LongArray,
        width: Int,
        height: Int,
    ): ByteBuffer {
        val expected = width.toLong() * height.toLong() * 2L
        val available = byteCounts.sum()
        if (available < expected) {
            throw DngParseException("CFA data is truncated: $available bytes for $expected expected")
        }
        if (offsets.size == 1 && byteOrder == ByteOrder.LITTLE_ENDIAN) {
            return buffer.duplicate().apply {
                order(ByteOrder.LITTLE_ENDIAN)
                position(offsets[0].toInt())
            }.slice().order(ByteOrder.LITTLE_ENDIAN)
        }

        val plane = ByteArray(expected.toInt())
        var written = 0
        for (i in offsets.indices) {
            val start = offsets[i].toInt()
            val count = minOf(byteCounts[i].toInt(), plane.size - written)
            if (count <= 0) break
            for (b in 0 until count) plane[written + b] = buffer.get(start + b)
            written += count
        }
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            var i = 0
            while (i + 1 < plane.size) {
                val swap = plane[i]
                plane[i] = plane[i + 1]
                plane[i + 1] = swap
                i += 2
            }
        }
        return ByteBuffer.wrap(plane).order(ByteOrder.LITTLE_ENDIAN)
    }

    /** DNG stores `AsShotNeutral` as the reciprocal of the white balance gains. */
    private fun gainsFromNeutral(neutral: DoubleArray): FloatArray? {
        if (neutral.size < 3 || neutral.any { it <= 0.0 }) return null
        // Normalizing on green matches COLOR_CORRECTION_GAINS, which the capture path feeds the
        // demosaic; the absolute scale of AsShotNeutral is not preserved by DNG writers.
        return floatArrayOf(
            (neutral[1] / neutral[0]).toFloat(),
            1f,
            (neutral[1] / neutral[2]).toFloat(),
        )
    }

    private fun matrixOf(values: DoubleArray): FloatArray? =
        if (values.size < 9) null else FloatArray(9) { values[it].toFloat() }

    private fun cfaPatternOf(values: LongArray): CfaPattern {
        // DNG colour codes: 0 red, 1 green, 2 blue.
        val codes = values.take(4).map { it.toInt() }
        return when {
            codes == listOf(0, 1, 1, 2) -> CfaPattern.RGGB
            codes == listOf(1, 0, 2, 1) -> CfaPattern.GRBG
            codes == listOf(1, 2, 0, 1) -> CfaPattern.GBRG
            codes == listOf(2, 1, 1, 0) -> CfaPattern.BGGR
            else -> CfaPattern.RGGB
        }
    }

    private fun readGainMaps(buffer: ByteBuffer, entry: TiffEntry): List<GainMap> {
        // Opcode payloads are big-endian regardless of the file's TIFF byte order.
        val bytes = ByteArray(entry.count) { buffer.get(entry.dataOffset + it) }
        val stream = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (stream.remaining() < 4) return emptyList()

        val maps = mutableListOf<GainMap>()
        val opcodeCount = stream.int
        repeat(opcodeCount.coerceAtMost(64)) {
            if (stream.remaining() < 16) return maps
            val id = stream.int
            stream.int // minimum DNG version
            stream.int // flags
            val parameterBytes = stream.int
            if (parameterBytes < 0 || parameterBytes > stream.remaining()) return maps
            val next = stream.position() + parameterBytes
            if (id == OPCODE_GAIN_MAP) {
                readGainMap(stream)?.let(maps::add)
            }
            stream.position(next)
        }
        return maps
    }

    private fun readGainMap(stream: ByteBuffer): GainMap? {
        if (stream.remaining() < 76) return null
        val top = stream.int
        val left = stream.int
        val bottom = stream.int
        val right = stream.int
        val plane = stream.int
        val planes = stream.int
        val rowPitch = stream.int
        val colPitch = stream.int
        val mapPointsV = stream.int
        val mapPointsH = stream.int
        val mapSpacingV = stream.double
        val mapSpacingH = stream.double
        val mapOriginV = stream.double
        val mapOriginH = stream.double
        val mapPlanes = stream.int
        if (mapPointsV <= 0 || mapPointsH <= 0 || mapPlanes <= 0) return null
        val gainCount = mapPointsV.toLong() * mapPointsH.toLong() * mapPlanes.toLong()
        if (gainCount * 4L > stream.remaining()) return null
        val gains = FloatArray(gainCount.toInt()) { stream.float }
        return GainMap(
            top, left, bottom, right, plane, planes, rowPitch, colPitch,
            mapPointsV, mapPointsH, mapSpacingV, mapSpacingH, mapOriginV, mapOriginH,
            mapPlanes, gains,
        )
    }

    private class TiffEntry(val type: Int, val count: Int, val dataOffset: Int)

    private fun readIfd(buffer: ByteBuffer, offset: Int): Map<Int, TiffEntry> {
        if (offset <= 0 || offset + 2 > buffer.capacity()) return emptyMap()
        val count = buffer.getShort(offset).toInt() and 0xFFFF
        val entries = HashMap<Int, TiffEntry>(count)
        for (i in 0 until count) {
            val position = offset + 2 + i * 12
            if (position + 12 > buffer.capacity()) break
            val tag = buffer.getShort(position).toInt() and 0xFFFF
            val type = buffer.getShort(position + 2).toInt() and 0xFFFF
            val valueCount = buffer.getInt(position + 4)
            if (valueCount < 0) continue
            val size = typeSize(type).toLong() * valueCount.toLong()
            if (size <= 0L || size > buffer.capacity()) continue
            val dataOffset = if (size <= 4L) position + 8 else buffer.getInt(position + 8)
            if (dataOffset < 0 || dataOffset + size > buffer.capacity()) continue
            entries[tag] = TiffEntry(type, valueCount, dataOffset)
        }
        return entries
    }

    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12 -> 8
        else -> 0
    }

    private fun scalar(buffer: ByteBuffer, entry: TiffEntry?): Long? =
        entry?.let { readLongs(buffer, it).firstOrNull() }

    private fun readLongs(buffer: ByteBuffer, entry: TiffEntry): LongArray = LongArray(entry.count) { i ->
        when (entry.type) {
            1, 2, 7 -> buffer.get(entry.dataOffset + i).toLong() and 0xFF
            6 -> buffer.get(entry.dataOffset + i).toLong()
            3 -> buffer.getShort(entry.dataOffset + i * 2).toLong() and 0xFFFF
            8 -> buffer.getShort(entry.dataOffset + i * 2).toLong()
            4 -> buffer.getInt(entry.dataOffset + i * 4).toLong() and 0xFFFFFFFFL
            9 -> buffer.getInt(entry.dataOffset + i * 4).toLong()
            else -> readDouble(buffer, entry, i).toLong()
        }
    }

    private fun readDoubles(buffer: ByteBuffer, entry: TiffEntry): DoubleArray =
        DoubleArray(entry.count) { i -> readDouble(buffer, entry, i) }

    private fun readDouble(buffer: ByteBuffer, entry: TiffEntry, index: Int): Double = when (entry.type) {
        5 -> {
            val numerator = buffer.getInt(entry.dataOffset + index * 8).toLong() and 0xFFFFFFFFL
            val denominator = buffer.getInt(entry.dataOffset + index * 8 + 4).toLong() and 0xFFFFFFFFL
            if (denominator == 0L) 0.0 else numerator.toDouble() / denominator.toDouble()
        }
        10 -> {
            val numerator = buffer.getInt(entry.dataOffset + index * 8)
            val denominator = buffer.getInt(entry.dataOffset + index * 8 + 4)
            if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()
        }
        11 -> buffer.getFloat(entry.dataOffset + index * 4).toDouble()
        12 -> buffer.getDouble(entry.dataOffset + index * 8)
        1, 2, 7 -> (buffer.get(entry.dataOffset + index).toInt() and 0xFF).toDouble()
        6 -> buffer.get(entry.dataOffset + index).toDouble()
        3 -> (buffer.getShort(entry.dataOffset + index * 2).toInt() and 0xFFFF).toDouble()
        8 -> buffer.getShort(entry.dataOffset + index * 2).toDouble()
        4 -> (buffer.getInt(entry.dataOffset + index * 4).toLong() and 0xFFFFFFFFL).toDouble()
        9 -> buffer.getInt(entry.dataOffset + index * 4).toDouble()
        else -> 0.0
    }
}
