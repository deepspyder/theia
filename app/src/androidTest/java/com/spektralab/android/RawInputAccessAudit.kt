package com.spektralab.android

import android.app.Instrumentation
import android.net.Uri
import androidx.core.content.FileProvider
import com.spektralab.android.engine.RawDecoder
import com.spektralab.android.engine.RawDecodeCancellation
import com.spektralab.android.engine.SpkException
import java.io.File

/** Local, seekable content and non-seekable provider inputs must produce identical decoded samples. */
internal class RawInputAccessAudit(private val test: Instrumentation) {
    fun run(): String {
        val context = test.targetContext
        val directory = File(context.cacheDir, "lighttable").apply { mkdirs() }
        val file = File.createTempFile("raw-access-", ".dng", directory)
        try {
            test.context.assets.open("audit.dng").use { source -> file.outputStream().use { source.copyTo(it) } }
            val fileUri = Uri.fromFile(file)
            val descriptorUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val pipeUri = Uri.parse("content://com.spektralab.android.test.raw-pipe/audit.dng")
            val expected = RawDecoder.decode(context, fileUri, halfSize = false)
            val matrix = RawDecoder.readCameraMatrix(context, fileUri)
            check(matrix == null || matrix.contentEquals(expected.camXyz))
            for (uri in listOf(descriptorUri, pipeUri)) {
                val actual = RawDecoder.decode(context, uri, halfSize = false)
                check(actual.width == expected.width && actual.height == expected.height)
                check(actual.linearProPhoto.contentEquals(expected.linearProPhoto)) { "RAW access path changed pixels" }
                check(actual.camXyz.contentEquals(expected.camXyz))
            }
            val exportReference = RawDecoder.decodeForExport(context, fileUri, 128).use { it.copySmallLinearRgb() }
            for (uri in listOf(descriptorUri, pipeUri)) {
                RawDecoder.decodeForExport(context, uri, 128).use {
                    check(it.copySmallLinearRgb().contentEquals(exportReference))
                }
            }
            verifyRecovery(file, expected.linearProPhoto, exportReference, matrix)
            check(context.cacheDir.listFiles().orEmpty().none {
                it.name.startsWith("raw_input_") || it.name.startsWith("raw_export_")
            }) { "Fallback RAW staging file leaked" }
            return "File/descriptor/pipe exact preview/export pixels; 21 recovery/slice/error/cancellation contracts passed; no staging leaks"
        } finally {
            file.delete()
        }
    }

    private fun verifyRecovery(file: File, pixels: FloatArray, exportPixels: FloatArray, matrix: FloatArray?) {
        val context = test.targetContext
        val authority = Uri.parse("content://com.spektralab.android.test.raw-pipe")
        val regular = Uri.withAppendedPath(authority, "regular.dng")
        val slice = Uri.withAppendedPath(authority, "slice.dng")
        val bytes = file.readBytes()
        var cases = 0
        fun <T> once(block: () -> T): T {
            context.contentResolver.call(authority, "reset", null, null)
            val value = block()
            check(context.contentResolver.call(authority, "stats", null, null)!!.getInt("opens") == 1) {
                "Provider was fetched more than once"
            }
            cases++
            return value
        }
        // Separate-UID provider fds may really reject /proc reopen under SELinux.
        // These calls use the actual native decoder, with no injected exception.
        once { check(RawDecoder.decode(context, regular, false).linearProPhoto.contentEquals(pixels)) }
        once { RawDecoder.decodeForExport(context, regular, 128).use { check(it.copySmallLinearRgb().contentEquals(exportPixels)) } }
        once {
            val actual = RawDecoder.readCameraMatrix(context, regular)
            check(actual == null && matrix == null || actual != null && matrix != null && actual.contentEquals(matrix))
        }
        for (stage in listOf("open", "metadata")) once {
            var calls = 0
            val actual = RawDecoder.withRawInput(context, regular, "raw_input_", null) { path ->
                if (++calls == 1) {
                    check(path.startsWith("/proc/self/fd/"))
                    // Emulate a provider fd that can be read but cannot be reopened by LibRaw.
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        android.os.ParcelFileDescriptor.fromFd(path.substringAfterLast('/').toInt())
                    ).use { check(it.read(ByteArray(17)) == 17) }
                    throw SpkException("RAW $stage: Input/output error")
                }
                check(!path.startsWith("/proc/"))
                RawDecoder.decode(context, Uri.fromFile(File(path)), false).linearProPhoto
            }
            check(calls == 2 && actual.contentEquals(pixels)) { "RAW reopen recovery changed pixels" }
        }
        once {
            var calls = 0
            val actual = RawDecoder.withRawInput(context, regular, "raw_export_", null) { path ->
                if (++calls == 1) throw SpkException("RAW open: Input/output error")
                RawDecoder.decodeForExport(context, Uri.fromFile(File(path)), 128).use { it.copySmallLinearRgb() }
            }
            check(calls == 2 && actual.contentEquals(exportPixels))
        }
        once {
            var calls = 0
            val actual = RawDecoder.withRawInput(context, regular, "raw_input_", null) { path ->
                if (++calls == 1) throw SpkException("RAW metadata: Input/output error")
                RawDecoder.readCameraMatrix(context, Uri.fromFile(File(path)))
            }
            check(calls == 2 && (actual == null && matrix == null || actual != null && matrix != null && actual.contentEquals(matrix)))
        }
        once {
            var calls = 0
            check(RawDecoder.withRawInput(context, regular, "raw_preview_", null) { path ->
                calls++
                check(path.startsWith("/proc/"))
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    android.os.ParcelFileDescriptor.fromFd(path.substringAfterLast('/').toInt())
                ).use { it.readBytes() }
            }.contentEquals(bytes) && calls == 1) { "Direct path no longer fast" }
        }
        once {
            var calls = 0
            val value: ByteArray? = RawDecoder.withRawInput(context, regular, "raw_preview_", null) { calls++; null }
            check(value == null && calls == 1) { "Absent embedded preview triggered a decode retry" }
        }
        for (message in listOf("RAW unpack: Input/output error", "RAW open: Unsupported file format or not RAW file",
            "RAW decode cancelled", "RAW process: Input/output error")) once {
            var calls = 0
            val original = SpkException(message)
            val error = runCatching { RawDecoder.withRawInput(context, regular, "raw_input_", null) {
                calls++; throw original
            } }.exceptionOrNull()
            check(error === original && calls == 1) { "Non-open failure was retried: $message" }
        }
        once {
            var calls = 0
            var cancelled = false
            val error = runCatching { RawDecoder.withRawInput(context, regular, "raw_input_", RawDecodeCancellation { cancelled }) {
                calls++; cancelled = true; throw SpkException("RAW open: Input/output error")
            } }.exceptionOrNull()
            check(error?.message == "RAW decode cancelled" && calls == 1) { "Cancellation retried decoding" }
        }
        once {
            val original = SpkException("RAW open: Input/output error")
            val fallback = SpkException("RAW unpack: damaged data")
            var calls = 0
            val error = runCatching { RawDecoder.withRawInput(context, regular, "raw_input_", null) {
                if (++calls == 1) throw original else throw fallback
            } }.exceptionOrNull()
            check(error === fallback && calls == 2 && error.suppressed.single() === original)
        }
        once {
            val actual = RawDecoder.withRawInput(context, slice, "raw_input_", null) { path ->
                check(!path.startsWith("/proc/"))
                File(path).readBytes()
            }
            check(actual.contentEquals(bytes)) { "Asset offset/length lost" }
        }
        once { check(RawDecoder.decode(context, slice, false).linearProPhoto.contentEquals(pixels)) }
        once { RawDecoder.decodeForExport(context, slice, 128).use { check(it.copySmallLinearRgb().contentEquals(exportPixels)) } }
        once {
            var decoded = false
            val error = runCatching { RawDecoder.withRawInput(context, Uri.withAppendedPath(authority, "truncated.dng"),
                "raw_input_", null) { decoded = true } }.exceptionOrNull()
            check(!decoded && error is java.io.EOFException) { "Truncated provider asset was passed to decode" }
        }
        once {
            var polls = 0
            var decoded = false
            val error = runCatching { RawDecoder.withRawInput(context, slice, "raw_input_", RawDecodeCancellation { ++polls >= 3 }) {
                decoded = true
            } }.exceptionOrNull()
            check(!decoded && error?.message == "RAW decode cancelled")
        }
        context.contentResolver.call(authority, "reset", null, null)
        var decoded = false
        val error = runCatching { RawDecoder.withRawInput(context, regular, "raw_input_", RawDecodeCancellation { true }) {
            decoded = true
        } }.exceptionOrNull()
        check(!decoded && error?.message == "RAW decode cancelled")
        check(context.contentResolver.call(authority, "stats", null, null)!!.getInt("opens") == 0)
        cases++
        check(cases == 21) { "Missing RAW recovery case: $cases" }
    }
}
