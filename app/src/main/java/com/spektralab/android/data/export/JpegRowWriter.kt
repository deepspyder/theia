package com.spektralab.android.data.export

import android.content.Context
import android.os.ParcelFileDescriptor
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.NativeJpegEncoder
import com.spektralab.android.engine.Rgba16Source
import java.io.BufferedOutputStream
import java.io.OutputStream

/** Exact graded rows in anonymous file-backed storage; the platform's existing JPEG codec consumes them. */
internal object JpegRowWriter {
    val available: Boolean get() = NativeJpegEncoder.available

    fun write(context: Context, source: Rgba16Source, grade: Grade, quality: Int, output: OutputStream,
              check: () -> Unit, metadata: ExportMetadata = ExportMetadata.EMPTY) {
        require(available)
        check(context.cacheDir.usableSpace > source.width.toLong() * source.height * 4 + 64L * 1024 * 1024) {
            "Insufficient system JPEG staging space"
        }
        EphemeralExportFile.create(context).use { storage ->
            ParcelFileDescriptor.AutoCloseOutputStream(storage.writer).use { file ->
                val rgba = ByteArray(source.width * minOf(32, source.height) * 4)
                Rgba16Strips(source, grade).forEach(check) { _, rows, argb ->
                    for (i in 0 until source.width * rows) {
                        val pixel = argb[i]
                        rgba[i * 4] = (pixel ushr 16).toByte(); rgba[i * 4 + 1] = (pixel ushr 8).toByte()
                        rgba[i * 4 + 2] = pixel.toByte(); rgba[i * 4 + 3] = 255.toByte()
                    }
                    file.write(rgba, 0, source.width * rows * 4)
                }
            }
            val buffered = BufferedOutputStream(output, 64 * 1024)
            val exif = if (metadata.attributes.isEmpty()) null else
                byteArrayOf(69, 120, 105, 102, 0, 0) + metadata.tiffDirectory(source.width, source.height)
            require(exif == null || exif.size + 2 <= 65535) { "JPEG metadata exceeds APP1 limit" }
            val stream = object : OutputStream() {
                private var prefix = 0
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    check()
                    var at = offset; var remaining = length
                    while (prefix < 2 && remaining > 0) {
                        val expected = if (prefix == 0) 255 else 216
                        check(bytes[at].toInt() and 255 == expected) { "Invalid JPEG SOI" }
                        buffered.write(expected); prefix++; at++; remaining--
                        if (prefix == 2 && exif != null) {
                            buffered.write(255); buffered.write(225)
                            buffered.write((exif.size + 2) ushr 8); buffered.write((exif.size + 2) and 255)
                            buffered.write(exif)
                        }
                    }
                    if (remaining > 0) buffered.write(bytes, at, remaining)
                }
            }
            check()
            NativeJpegEncoder.encode(storage.reader.fd, source.width, source.height, quality.coerceIn(1, 100), stream)
            check(); buffered.flush()
        }
    }
}
