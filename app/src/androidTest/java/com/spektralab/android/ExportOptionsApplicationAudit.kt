package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Real editor/host/cache/encoder outputs, checked externally against independently sized full renders. */
internal class ExportOptionsApplicationAudit(private val test: Instrumentation, private val output: File) {
    private fun await(vm: EditorViewModel, done: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!done()) { check(SystemClock.elapsedRealtime() - start < 240_000) { "R02 timeout: ${vm.errorMessage}" }; Thread.sleep(40) }
        test.waitForIdleSync()
    }

    fun run(): String {
        val context = test.targetContext
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        fun main(action: () -> Unit) = test.runOnMainSync(action)
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val directory = File(output, "r02-output").apply { mkdirs() }
        val owned = mutableListOf<File>()
        val rows = JSONArray()
        val frames = listOf("png-crop", "jpeg-exif", "webp-film", "dng", "nef")
        val limits = listOf(ExportSize(ExportSize.Kind.LONG_EDGE, 113.0),
            ExportSize(ExportSize.Kind.SHORT_EDGE, 79.0), ExportSize(ExportSize.Kind.MEGAPIXELS, .02))
        fun saved(): Uri = Uri.parse(vm.log.substringAfterLast("相册: ").lineSequence().first().trim()).also { check(it.scheme == "content") }
        fun copyResult(uri: Uri, file: File): String {
            try {
                context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                return context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)!!.use {
                    check(it.moveToFirst()); it.getString(0)
                }
            } finally { context.contentResolver.delete(uri, null, null) }
        }
        return try {
            await(vm) { vm.engine != null && !vm.busy }
            for (frame in frames) {
                val extension = when (frame) { "png-crop" -> "png"; "jpeg-exif" -> "jpg"; "webp-film" -> "webp"; "dng" -> "dng"; else -> "NEF" }
                val source = File(directory, "r02-$frame.$extension").also { owned += it }
                if (frame == "dng") test.context.assets.open("audit.dng").use { input -> source.outputStream().use { input.copyTo(it) } }
                else if (frame == "nef") File(output, "pc-parity-DSC_5099.NEF").copyTo(source, overwrite = true)
                else {
                    val image = Bitmap.createBitmap(601, 403, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(601 * 403) { i -> 0xff000000.toInt() or
                        (((i % 601) * 255 / 600) shl 16) or (((i / 601) * 255 / 402) shl 8) or ((i * 17) and 255) }
                    image.setPixels(pixels, 0, 601, 0, 0, 601, 403)
                    try { source.outputStream().use { image.compress(when (frame) {
                        "jpeg-exif" -> Bitmap.CompressFormat.JPEG; "webp-film" -> Bitmap.CompressFormat.WEBP_LOSSLESS
                        else -> Bitmap.CompressFormat.PNG
                    }, 95, it) } } finally { image.recycle() }
                    if (frame == "jpeg-exif") ExifInterface(source).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes()
                    }
                }
                val uri = Uri.fromFile(source)
                val edit = EditorParams().apply {
                    grainActive = false; glareActive = false; halationActive = false
                    brightness = 3f; vignetteAmount = 7f
                    if (frame == "png-crop") { cropW = .63f; cropH = .83f; quarterTurns = 1; flipH = true }
                    if (frame == "webp-film") { overscanActive = true; overscanFormat = "135_half"; dateImprintActive = true; dateImprintText = "'26 11 18" }
                }
                EditStore.save(context, uri, edit)
                main { vm.smartAutoEnabled = false; vm.dismissError(); vm.openPhotos(listOf(uri)) }
                await(vm) { vm.errorMessage != null || vm.hasPhoto && !vm.busy && vm.rendered != null && vm.renderTaskState.active == null }
                check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                val grade = EditorViewModel::class.java.getDeclaredMethod("grade", EditorParams::class.java).apply { isAccessible = true }.invoke(vm, edit) as Grade
                val selections: List<Pair<Int, ExportSize?>> = limits.map { 0 to it } +
                    if (frame == "png-crop") listOf(2048 to null, 2560 to null, 3840 to null, 0 to null) else emptyList()
                for ((selection, custom) in selections.withIndex().map { it.index to it.value }) {
                    val (legacy, size) = custom
                    val prefix = "$frame-$selection"
                    main { vm.engine?.park() }
                    var expectedWidth = 0
                    var expectedHeight = 0
                    EngineClient.create(context).use { engine ->
                        ExportSourceDecoder.open(context, engine, uri, extension.lowercase() in listOf("nef", "dng"), edit,
                            ExportOptions("TIFF", legacy, "sRGB", 95, false, false, true)) {}
                        engine.renderExportOnce().use { rendered ->
                            val scale = if (size == null) 1.0 else when (size.kind) {
                                ExportSize.Kind.LONG_EDGE -> size.value / maxOf(rendered.width, rendered.height)
                                ExportSize.Kind.SHORT_EDGE -> size.value / minOf(rendered.width, rendered.height)
                                ExportSize.Kind.MEGAPIXELS -> sqrt(size.value * 1e6 / rendered.width / rendered.height)
                            }.coerceAtMost(1.0)
                            expectedWidth = (rendered.width * scale).roundToInt().coerceAtLeast(1)
                            expectedHeight = (rendered.height * scale).roundToInt().coerceAtLeast(1)
                            val reference = AreaReference(rendered, expectedWidth, expectedHeight)
                            File(directory, "$prefix-reference.png").outputStream().use { PngRowWriter.write(reference, grade, it, {}) }
                            File(directory, "$prefix-reference.tiff").outputStream().use { TiffWriter.writeNative(reference, grade, null, it, {}) }
                            File(directory, "$prefix-reference.jpg").outputStream().use { JpegRowWriter.write(context, reference, grade, 95, it, {}) }
                        }
                    }
                    for (format in listOf("JPEG", "PNG", "TIFF")) {
                        val start = SystemClock.elapsedRealtime()
                        main {
                            vm.dismissError(); vm.exportFormat = format; vm.exportRes = legacy; vm.exportCustomSize = size
                            vm.exportColorSpace = "sRGB"; vm.exportKeepCapture = false; vm.exportKeepLocation = false
                            vm.exportNaming = ExportNaming(listOf(ExportNaming.Token.ORIGINAL, ExportNaming.Token.DATE), "-")
                            vm.export()
                            // The request must retain both sizing and naming after UI values change.
                            vm.exportCustomSize = ExportSize(ExportSize.Kind.LONG_EDGE, 1.0)
                            vm.exportNaming = ExportNaming(listOf(ExportNaming.Token.PAPER))
                        }
                        await(vm) { !vm.isExporting && !vm.busy }
                        check(vm.errorMessage == null) { "$prefix/$format: ${vm.errorMessage}" }
                        val ext = when (format) { "JPEG" -> "jpg"; "TIFF" -> "tiff"; else -> "png" }
                        val file = File(directory, "$prefix.$ext")
                        val display = copyResult(saved(), file)
                        check(display.startsWith(source.nameWithoutExtension + "-")) { "Queued name was changed: $display" }
                        rows.put(JSONObject().put("file", file.name).put("reference", "$prefix-reference.$ext")
                            .put("width", expectedWidth).put("height", expectedHeight).put("name", display)
                            .put("format", format).put("input", frame).put("size", size?.toJson() ?: legacy)
                            .put("elapsed_ms", SystemClock.elapsedRealtime() - start))
                        File(directory, "results.json").writeText(rows.toString(2))
                    }
                }
            }
            "${rows.length()} actual editor exports: PNG/JPEG/WebP/DNG/24MP NEF; long/short/MP, crop/turn/EXIF/film, frozen name/size and all four legacy resolutions; external pixel references retained"
        } finally { main { vm.exportCustomSize = null; vm.exportNaming = ExportNaming(); activity.finish() }; owned.forEach { it.delete() } }
    }

    /** Independent 2D footprint integration; no production separable resizer is called. */
    private class AreaReference(private val source: Rgba16Source, override val width: Int, override val height: Int) : Rgba16Source {
        private val input = ShortArray(source.width * 4)
        override fun readRows(y: Int, rows: Int, destination: ShortArray) {
            if (width == source.width && height == source.height) { source.readRows(y, rows, destination); return }
            for (dy in y until y + rows) {
                val sums = DoubleArray(width * 4)
                val top = dy.toDouble() * source.height / height
                val bottom = (dy + 1.0) * source.height / height
                for (sy in floor(top).toInt() until minOf(ceil(bottom).toInt(), source.height)) {
                    source.readRows(sy, 1, input)
                    for (dx in 0 until width) {
                        val left = dx.toDouble() * source.width / width
                        val right = (dx + 1.0) * source.width / width
                        for (sx in floor(left).toInt() until minOf(ceil(right).toInt(), source.width)) {
                            val area = (minOf(bottom, sy + 1.0) - maxOf(top, sy.toDouble())) *
                                (minOf(right, sx + 1.0) - maxOf(left, sx.toDouble()))
                            for (c in 0..3) sums[dx * 4 + c] += (input[sx * 4 + c].toInt() and 65535) * area
                        }
                    }
                }
                val area = source.width.toDouble() / width * source.height / height
                for (i in sums.indices) destination[(dy - y) * width * 4 + i] = (sums[i] / area).roundToInt().coerceIn(0, 65535).toShort()
            }
        }
    }
}
