package com.kbbench.app.preprocessing

import com.kbbench.algorithm.base.Frame
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
    const val VERSION = 1

    private const val MAGIC = 0x4B424631
    private const val HEADER_BYTES = 20L
    private const val IO_BUFFER_SIZE = 64 * 1024

    fun save(file: File, frame: Frame) {
        val sampleCount = frame.size.toLong()
        val expectedBytes = expectedBytes(frame.width, frame.height)
        require(expectedBytes == HEADER_BYTES + sampleCount * 6) { "Frame artifact size overflow" }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temporary), IO_BUFFER_SIZE)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(frame.width)
                output.writeInt(frame.height)
                output.writeInt(frame.sourceDepth)
                writePlane(output, frame.red)
                writePlane(output, frame.green)
                writePlane(output, frame.blue)
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
                if (input.readInt() != VERSION) throw IOException("Unsupported frame artifact: ${file.name}")
                val width = input.readInt()
                val height = input.readInt()
                val sourceDepth = input.readInt()
                require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE) {
                    "Invalid frame artifact dimensions: ${width}x$height"
                }
                val expectedBytes = expectedBytes(width, height)
                if (fileLength != expectedBytes) {
                    throw IOException("Unexpected frame artifact size for ${file.name}")
                }
                val sampleCount = width * height
                val red = readPlane(input, sampleCount)
                val green = readPlane(input, sampleCount)
                val blue = readPlane(input, sampleCount)
                return Frame(red, green, blue, width, height, sourceDepth)
            } catch (e: EOFException) {
                throw IOException("Truncated frame artifact: ${file.name}", e)
            }
        }
    }

    private fun expectedBytes(width: Int, height: Int): Long {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE) {
            "Invalid frame artifact dimensions: ${width}x$height"
        }
        return HEADER_BYTES + width.toLong() * height * 6
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
}
