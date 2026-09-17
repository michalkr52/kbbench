package com.kbbench.app.preprocessing

import com.kbbench.algorithm.base.Frame
import com.kbbench.algorithm.base.FrameDomain
import com.kbbench.algorithm.base.FrameStorage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Lossless on-disk representation of an exact planar algorithm input or output frame. */
object FrameArtifact {
    const val FORMAT = "KBFRAME"
    const val VERSION = 2

    private const val MAGIC = 0x4B424631
    private const val LEGACY_VERSION = 1
    private const val LEGACY_HEADER_BYTES = 20L
    private const val HEADER_BYTES = 28L
    private const val IO_BUFFER_SIZE = 64 * 1024

    fun save(file: File, frame: Frame) {
        val sampleCount = frame.size.toLong()
        val expectedBytes = expectedBytes(frame.width, frame.height, frame.storage)
        require(expectedBytes > sampleCount) { "Frame artifact size overflow" }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temporary), IO_BUFFER_SIZE)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(frame.width)
                output.writeInt(frame.height)
                output.writeInt(frame.sourceDepth)
                output.writeInt(frame.domain.ordinal)
                output.writeInt(frame.storage.ordinal)
                when (frame.storage) {
                    FrameStorage.BOUNDED_U16 -> {
                        writePlane(output, frame.red)
                        writePlane(output, frame.green)
                        writePlane(output, frame.blue)
                    }
                    FrameStorage.UNCLIPPED_FLOAT -> {
                        writeFloatPlane(output, frame.floatPlane(0))
                        writeFloatPlane(output, frame.floatPlane(1))
                        writeFloatPlane(output, frame.floatPlane(2))
                    }
                }
            }
            if (file.exists() && !file.delete()) {
                throw IOException("Unable to replace ${file.name}")
            }
            if (!temporary.renameTo(file)) {
                throw IOException("Unable to finalize ${file.name}")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun load(file: File): Frame {
        if (!file.isFile) throw IOException("Missing frame artifact: ${file.name}")
        val fileLength = file.length()
        DataInputStream(BufferedInputStream(FileInputStream(file), IO_BUFFER_SIZE)).use { input ->
            try {
                if (input.readInt() != MAGIC) throw IOException("Invalid frame artifact: ${file.name}")
                val version = input.readInt()
                if (version != LEGACY_VERSION && version != VERSION) {
                    throw IOException("Unsupported frame artifact: ${file.name}")
                }
                val width = input.readInt()
                val height = input.readInt()
                val sourceDepth = input.readInt()
                require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE) {
                    "Invalid frame artifact dimensions: ${width}x$height"
                }
                val domain: FrameDomain
                val storage: FrameStorage
                if (version == LEGACY_VERSION) {
                    domain = FrameDomain.SRGB
                    storage = FrameStorage.BOUNDED_U16
                } else {
                    domain = FrameDomain.entries.getOrNull(input.readInt())
                        ?: throw IOException("Invalid frame domain in ${file.name}")
                    storage = FrameStorage.entries.getOrNull(input.readInt())
                        ?: throw IOException("Invalid frame storage in ${file.name}")
                }
                val expectedBytes = expectedBytes(
                    width,
                    height,
                    storage,
                    headerBytes = if (version == LEGACY_VERSION) LEGACY_HEADER_BYTES else HEADER_BYTES,
                )
                if (fileLength != expectedBytes) {
                    throw IOException("Unexpected frame artifact size for ${file.name}")
                }
                val sampleCount = width * height
                return when (storage) {
                    FrameStorage.BOUNDED_U16 -> Frame(
                        readPlane(input, sampleCount),
                        readPlane(input, sampleCount),
                        readPlane(input, sampleCount),
                        width,
                        height,
                        sourceDepth,
                        domain,
                    )
                    FrameStorage.UNCLIPPED_FLOAT -> Frame.unclipped(
                        readFloatPlane(input, sampleCount),
                        readFloatPlane(input, sampleCount),
                        readFloatPlane(input, sampleCount),
                        width,
                        height,
                        sourceDepth,
                        domain,
                    )
                }
            } catch (e: EOFException) {
                throw IOException("Truncated frame artifact: ${file.name}", e)
            }
        }
    }

    private fun expectedBytes(
        width: Int,
        height: Int,
        storage: FrameStorage,
        headerBytes: Long = HEADER_BYTES,
    ): Long {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE) {
            "Invalid frame artifact dimensions: ${width}x$height"
        }
        val bytesPerSample = when (storage) {
            FrameStorage.BOUNDED_U16 -> 2L
            FrameStorage.UNCLIPPED_FLOAT -> 4L
        }
        return headerBytes +
            width.toLong() * height * bytesPerSample * 3
    }

    private fun writePlane(output: DataOutputStream, plane: ShortArray) {
        val bytes = ByteArray(IO_BUFFER_SIZE)
        var byteCount = 0
        for (sample in plane) {
            val value = sample.toInt() and 0xFFFF
            bytes[byteCount++] = (value ushr 8).toByte()
            bytes[byteCount++] = value.toByte()
            if (byteCount == bytes.size) {
                output.write(bytes)
                byteCount = 0
            }
        }
        if (byteCount != 0) output.write(bytes, 0, byteCount)
    }

    private fun writeFloatPlane(output: DataOutputStream, plane: FloatArray) {
        val bytes = ByteArray(IO_BUFFER_SIZE - IO_BUFFER_SIZE % Float.SIZE_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var sampleIndex = 0
        while (sampleIndex < plane.size) {
            val samples = minOf(bytes.size / Float.SIZE_BYTES, plane.size - sampleIndex)
            buffer.clear()
            repeat(samples) {
                buffer.putInt(plane[sampleIndex++].toRawBits())
            }
            output.write(bytes, 0, samples * Float.SIZE_BYTES)
        }
    }

    private fun readPlane(input: DataInputStream, sampleCount: Int): ShortArray {
        val plane = ShortArray(sampleCount)
        val bytes = ByteArray(IO_BUFFER_SIZE)
        var sampleIndex = 0
        while (sampleIndex < sampleCount) {
            val samples = minOf(bytes.size / 2, sampleCount - sampleIndex)
            input.readFully(bytes, 0, samples * 2)
            for (offset in 0 until samples) {
                val high = bytes[offset * 2].toInt() and 0xFF
                val low = bytes[offset * 2 + 1].toInt() and 0xFF
                plane[sampleIndex++] = ((high shl 8) or low).toShort()
            }
        }
        return plane
    }

    private fun readFloatPlane(input: DataInputStream, sampleCount: Int): FloatArray {
        val plane = FloatArray(sampleCount)
        val bytes = ByteArray(IO_BUFFER_SIZE - IO_BUFFER_SIZE % Float.SIZE_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var sampleIndex = 0
        while (sampleIndex < sampleCount) {
            val samples = minOf(bytes.size / Float.SIZE_BYTES, sampleCount - sampleIndex)
            input.readFully(bytes, 0, samples * Float.SIZE_BYTES)
            buffer.clear()
            repeat(samples) {
                plane[sampleIndex++] = Float.fromBits(buffer.int)
            }
        }
        return plane
    }
}
