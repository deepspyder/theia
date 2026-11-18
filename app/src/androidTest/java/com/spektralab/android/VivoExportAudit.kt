package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.ExportOptions
import com.spektralab.android.core.model.ExportRealisation
import com.spektralab.android.core.model.HalationModel
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.export.ExportSourceDecoder
import com.spektralab.android.data.export.PngRowWriter
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.NativeRenderResult
import com.spektralab.android.engine.StockCatalog
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/** Real customer JPEGs; an emulator pass cannot certify the customer's GPU driver. */
internal class VivoExportAudit(private val test: Instrumentation, private val output: File) {
    private val context = test.targetContext
    private val records = JSONArray()
    private val sources = listOf("vivo-tree.jpg", "vivo-street.jpg", "vivo-branches.jpg").map { File(output, it) }
    private val stocks = listOf("kodak_portra_400", "cinestill_800t", "fujifilm_velvia_100", "ilford_hp5_plus_400")

    private fun params(film: String, imprint: Boolean): EditorParams {
        val (films, papers) = StockCatalog.load(context)
        val stock = films.single { it.id == film }
        val paper = papers.firstOrNull { it.id == stock.targetPrint && StockCatalog.canPrint(film, it.id) }
            ?: papers.first { StockCatalog.canPrint(film, it.id) }
        return EditorParams().apply {
            filmId = film
            paperId = if (film == "cinestill_800t") "kodak_2383" else paper.id
            exposureEv = .35f
            brightness = .08f
            contrast = .12f
            dateImprintActive = imprint
            dateImprintText = "'26 11 16"
            dateImprintDataText = "ISO100 F1.8 1/125"
            if (film == "cinestill_800t") halationModelVersion = HalationModel.BACK_REFLECTION.version
        }
    }

    fun nativeParity(): String {
        sources.forEach { check(it.isFile) { "Missing customer fixture: ${it.name}" } }
        val inputs = sources + File(output, "pc-parity-DSC_5099.NEF").also { check(it.isFile) }
        for (source in inputs) {
            val raw = source.extension == "NEF"
            for (film in if (raw) listOf("cinestill_800t") else stocks) {
                for (imprint in if (raw) listOf(true) else listOf(false, true)) {
                    val edit = params(film, imprint)
                    val options = ExportOptions("PNG", if (raw) 0 else 2560, "sRGB", 100, false, false, true)
                    fun render(windowed: Boolean): JSONObject = EngineClient.create(context).use { engine ->
                        val start = SystemClock.elapsedRealtime()
                        ExportSourceDecoder.open(context, engine, Uri.fromFile(source), raw, edit, options) {}
                        val seed = ExportRealisation.fresh(edit)
                        val frame = if (windowed) engine.renderExportResolved(seed.film, seed.print) else engine.renderForExport()
                        frame.use {
                            JSONObject().put("width", it.width).put("height", it.height)
                                .put("sha256_u16", digest(it)).put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                                .put("memory", JSONObject(engine.memoryReport()))
                        }
                    }
                    val reference = render(false)
                    val actual = render(true)
                    val record = JSONObject().put("input", source.name).put("film", film).put("paper", edit.paperId)
                        .put("imprint", imprint).put("reference", reference).put("actual", actual)
                    records.put(record)
                    save("vivo-native.json")
                    check(reference.getInt("width") == actual.getInt("width") && reference.getInt("height") == actual.getInt("height"))
                    check(reference.getString("sha256_u16") == actual.getString("sha256_u16")) {
                        "Whole/windowed RGBA16 differs: $record"
                    }
                }
            }
        }
        return "${records.length()} whole/windowed RGBA16 hashes identical, including ${sources.size} customer JPEGs, imprint on/off and full-size NEF"
    }

    fun application(): String {
        sources.forEach { check(it.isFile) }
        val created = mutableListOf<Uri>()
        val owned = mutableListOf<File>()
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        fun main(action: () -> Unit) = test.runOnMainSync { action() }
        fun await(condition: () -> Boolean) {
            val start = SystemClock.elapsedRealtime()
            while (!condition()) {
                check(SystemClock.elapsedRealtime() - start < 240_000) { vm.errorMessage ?: "Export audit timed out" }
                SystemClock.sleep(30)
            }
            test.waitForIdleSync()
        }
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val oldAuto = vm.smartAutoEnabled
        try {
            await { vm.engine != null && !vm.busy }
            for (source in sources) for (film in stocks) for (imprint in if (film == stocks.first()) listOf(false, true) else listOf(true)) {
                val edit = params(film, imprint)
                val photo = File(output, "vivo-owned-$film-$imprint-${source.name}")
                source.copyTo(photo, overwrite = true)
                owned += photo
                val uri = Uri.fromFile(photo)
                EditStore.save(context, uri, edit)
                main { vm.smartAutoEnabled = false; vm.dismissError(); vm.openPhotos(listOf(uri)) }
                await { vm.rendered != null && !vm.busy && vm.renderTaskState.active == null }
                check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                check(vm.params.filmId == film && vm.params.dateImprintActive == imprint)
                val grade = EditorViewModel::class.java.getDeclaredMethod("grade", EditorParams::class.java)
                    .apply { isAccessible = true }.invoke(vm, edit) as Grade
                val sizes = if (film == stocks.first()) listOf(2048, 2560, 3840, 0) else listOf(2560)
                for (size in sizes) {
                    val prefix = "vivo-${source.nameWithoutExtension}-$film-$imprint-$size"
                    val options = ExportOptions("PNG", size, "sRGB", 100, false, false, true)
                    EngineClient.create(context).use { engine ->
                        ExportSourceDecoder.open(context, engine, uri, false, edit, options) {}
                        engine.renderForExport().use { frame ->
                            File(output, "$prefix-reference.png").outputStream().use { PngRowWriter.write(frame, grade, it, {}) }
                            File(output, "$prefix-reference.tiff").outputStream().use { TiffWriter.writeNative(frame, grade, null, it, {}) }
                        }
                    }
                    ExportRenderCache.get(context).clearDisposable()
                    for (format in listOf("JPEG", "PNG", "TIFF")) {
                        val start = SystemClock.elapsedRealtime()
                        main {
                            vm.dismissError(); vm.exportFormat = format; vm.exportRes = size
                            vm.exportColorSpace = "sRGB"; vm.jpegQuality = 100; vm.export()
                        }
                        await { !vm.isExporting && !vm.busy }
                        check(vm.errorMessage == null) { "$prefix $format: ${vm.errorMessage}" }
                        val saved = Uri.parse(vm.log.substringAfterLast("相册: ").lineSequence().first().trim())
                        check(saved.scheme == "content")
                        created += saved
                        val file = File(output, "$prefix.${format.lowercase()}")
                        context.contentResolver.openInputStream(saved)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                        val record = JSONObject().put("input", source.name).put("film", film).put("paper", edit.paperId)
                            .put("imprint", imprint).put("size", size).put("format", format).put("file", file.name)
                            .put("reference", "$prefix-reference").put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                        if (format != "TIFF") compare(file, File(output, "$prefix-reference.png"), record, format == "PNG")
                        records.put(record)
                        save("vivo-application.json")
                    }
                }
            }
            return "${records.length()} actual application exports; ${sources.size} customer JPEGs, four stocks, imprint on/off, four sizes, JPEG/PNG/TIFF"
        } catch (failure: Throwable) {
            File(output, "vivo-export-failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally {
            created.forEach { context.contentResolver.delete(it, null, null) }
            main { vm.smartAutoEnabled = oldAuto; activity.finish() }
            owned.forEach { it.delete() }
        }
    }

    private fun compare(file: File, reference: File, record: JSONObject, lossless: Boolean) {
        val actual = checkNotNull(BitmapFactory.decodeFile(file.path))
        val expected = checkNotNull(BitmapFactory.decodeFile(reference.path))
        try {
            check(actual.width == expected.width && actual.height == expected.height)
            val a = IntArray(actual.width * 16)
            val b = IntArray(a.size)
            var sum = 0L
            var maximum = 0
            for (y in 0 until actual.height step 16) {
                val rows = minOf(16, actual.height - y)
                actual.getPixels(a, 0, actual.width, 0, y, actual.width, rows)
                expected.getPixels(b, 0, expected.width, 0, y, expected.width, rows)
                for (i in 0 until actual.width * rows) for (channel in 0..2) {
                    val shift = channel * 8
                    val difference = abs((a[i] ushr shift and 255) - (b[i] ushr shift and 255))
                    sum += difference
                    maximum = maxOf(maximum, difference)
                }
            }
            val mean = sum.toDouble() / (actual.width.toLong() * actual.height * 3)
            record.put("max_u8", maximum).put("mean_u8", mean).put("width", actual.width).put("height", actual.height)
            check(if (lossless) maximum == 0 else mean < 3.0) { "Export colour mismatch: $record" }
        } finally {
            actual.recycle()
            expected.recycle()
        }
    }

    private fun digest(frame: NativeRenderResult): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val values = ShortArray(frame.width * 16 * 4)
        val bytes = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until frame.height step 16) {
            val rows = minOf(16, frame.height - y)
            frame.readRows(y, rows, values)
            bytes.clear()
            for (i in 0 until frame.width * rows * 4) bytes.putShort(values[i])
            digest.update(bytes.array(), 0, bytes.position())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun save(name: String) = File(output, name).writeText(records.toString(2))
}
