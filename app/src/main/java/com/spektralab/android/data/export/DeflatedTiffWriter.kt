package com.spektralab.android.data.export

import android.content.Context
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.Rgba16Source
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream

/** Optional lossless TIFF Deflate. Existing RGB16/ICC/EXIF bytes remain authoritative. */
internal object DeflatedTiffWriter {
    fun write(context: Context, source: Rgba16Source, grade: Grade, icc: ByteArray?, output: OutputStream,
              check: () -> Unit, metadata: ExportMetadata) {
        check(context.cacheDir.usableSpace > source.width.toLong() * source.height * 12 + 64L * 1024 * 1024) {
            "Insufficient lossless TIFF staging space"
        }
        val raw = File.createTempFile(".tiff-raw-", ".tmp", context.cacheDir)
        var compressed: File? = null
        try {
            compressed = File.createTempFile(".tiff-deflate-", ".tmp", context.cacheDir)
            raw.outputStream().buffered().use { TiffWriter.writeNative(source, grade, icc, it, check, metadata) }
            RandomAccessFile(raw, "r").use { input ->
                val first = ByteArray(10); input.readFully(first)
                val root = ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN)
                require(root.getShort(0).toInt() == 0x4949 && root.getShort(2).toInt() == 42 && root.getInt(4) == 8)
                val count = root.getShort(8).toInt() and 65535
                require(count in 1..256)
                val entries = ByteArray(count * 12 + 4); input.readFully(entries)
                val table = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN)
                fun entry(tag: Int): Int = (0 until count).first { table.getShort(it * 12).toInt() and 65535 == tag } * 12
                val offset = table.getInt(entry(273) + 8)
                val length = table.getInt(entry(279) + 8)
                require(offset in (10 + entries.size)..1024*1024 && length.toLong() == source.width.toLong() * source.height * 6)
                val header = ByteArray(offset); input.seek(0); input.readFully(header)
                compressed.outputStream().buffered().use { file ->
                    DeflaterOutputStream(file).use { deflater ->
                        val buffer = ByteArray(65536); var remaining = length
                        while (remaining > 0) {
                            check(); val n = input.read(buffer, 0, minOf(buffer.size, remaining)); check(n > 0)
                            deflater.write(buffer, 0, n); remaining -= n
                        }
                    }
                }
                require(compressed.length() in 1..Int.MAX_VALUE.toLong())
                val patched = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                patched.putShort(10 + entry(259) + 8, 8) // Adobe Deflate, no predictor or sample conversion.
                patched.putInt(10 + entry(279) + 8, compressed.length().toInt())
                check(); output.write(header)
                compressed.inputStream().use { stream ->
                    val buffer = ByteArray(65536)
                    while (true) { check(); val n = stream.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
                }
            }
        } finally { raw.delete(); compressed?.delete() }
    }
}
