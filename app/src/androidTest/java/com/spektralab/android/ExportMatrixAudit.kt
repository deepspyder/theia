package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.*
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.export.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Formal editor -> render host -> encoder -> MediaStore, with an independent whole-dispatch raster. */
internal class ExportMatrixAudit(private val test: Instrumentation, private val output: File) {
    fun performance(): String {
        val context = test.targetContext
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        fun main(action: () -> Unit) = test.runOnMainSync(action)
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        fun await(done: () -> Boolean) {
            val start = SystemClock.elapsedRealtime()
            while (!done()) { check(SystemClock.elapsedRealtime() - start < 240_000) { vm.errorMessage.orEmpty() }; Thread.sleep(30) }
            test.waitForIdleSync()
        }
        val report = JSONArray()
        val owned = mutableListOf<File>()
        try {
            await { vm.engine != null && !vm.busy }
            for (layout in listOf("plain", "135-strip", "135-filed")) {
                val photo = File(output, "performance-owned-$layout.NEF")
                File(output, "pc-parity-DSC_5099.NEF").copyTo(photo, overwrite = true); owned += photo
                val uri = Uri.fromFile(photo)
                EditStore.save(context, uri, EditorParams().apply {
                    filmId = "cinestill_800t"; paperId = "kodak_2383"; halationModelVersion = 2
                    overscanActive = layout != "plain"; overscanMode = layout.substringAfter('-', "strip")
                })
                main { vm.smartAutoEnabled = false; vm.dismissError(); vm.openPhotos(listOf(uri)) }
                await { vm.errorMessage != null || vm.rendered != null && !vm.busy && vm.renderTaskState.active == null }
                check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                var expected: String? = null
                repeat(3) { iteration ->
                    for (cached in if (layout == "plain") listOf(false, true) else listOf(false)) {
                        val cache = ExportRenderCache.get(context)
                        if (!cached) cache.clearDisposable()
                        val hits = cache.stats.hits
                        val start = SystemClock.elapsedRealtime()
                        main {
                            vm.dismissError(); vm.exportRes = 0; vm.exportFormat = "JPEG"; vm.jpegQuality = 95
                            vm.exportKeepCapture = false; vm.exportKeepLocation = false; vm.export()
                        }
                        await { !vm.isExporting && !vm.busy }
                        val elapsed = SystemClock.elapsedRealtime() - start
                        check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                        val saved = Uri.parse(vm.log.substringAfterLast("相册: ").lineSequence().first().trim())
                        check(saved.scheme == "content")
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        try { context.contentResolver.openInputStream(saved)!!.use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                        } } finally { context.contentResolver.delete(saved, null, null) }
                        val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        if (expected == null) expected = sha else check(expected == sha) { "Repeated performance export changed image bytes" }
                        check(!cached || cache.stats.hits > hits) { "Expected cache hit did not occur" }
                        report.put(JSONObject().put("layout", layout).put("iteration", iteration).put("cached", cached)
                            .put("elapsed_ms", elapsed).put("sha256", sha))
                        File(output, "export-performance.json").writeText(report.toString(2))
                    }
                }
            }
        } finally { main { activity.finish() }; owned.forEach { it.delete() } }
        return "${report.length()} original NEF JPEG95 exports; three cold samples per layout and three confirmed plain cache hits; repeated file hashes identical"
    }

    fun run(part: String): String {
        val context = test.targetContext
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        fun main(action: () -> Unit) = test.runOnMainSync(action)
        fun await(done: () -> Boolean) {
            val start = SystemClock.elapsedRealtime()
            while (!done()) { check(SystemClock.elapsedRealtime() - start < 240_000) { "Matrix timeout: ${vm.errorMessage}" }; Thread.sleep(30) }
            test.waitForIdleSync()
        }
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val rows = JSONArray()
        val owned = mutableListOf<File>()
        val sourceNames = if (part == "raw") listOf("pc-parity-DSC_5099.NEF")
            else if (part == "large") listOf("matrix-raw.dng")
            else listOf("matrix-photo.jpg", "matrix-photo.png", "matrix-photo.webp", "matrix-raw.dng")
        try {
            await { vm.engine != null && !vm.busy }
            for (sourceName in sourceNames) {
                val source = File(output, sourceName); check(source.isFile) { "Missing $sourceName" }
                val raw = RawDecoder.isRaw(context, Uri.fromFile(source), sourceName)
                val layouts = if (part == "raw") listOf("plain", "135-strip", "135-filed", "135_half-strip") else listOf("135-strip")
                for (layout in layouts) {
                    val edit = EditorParams().apply {
                        filmId = "cinestill_800t"; paperId = "kodak_2383"; halationModelVersion = 2
                        overscanActive = layout != "plain"
                        if (overscanActive) { overscanFormat = layout.substringBefore('-'); overscanMode = layout.substringAfter('-') }
                        dateImprintActive = true; dateImprintText = "'26 11 17"
                        brightness = 3f; saturation = 5f; vignetteAmount = 8f
                    }
                    // Opening saves the outgoing photo. Separate identities
                    // keep that save from overwriting the next fixture's edit.
                    val photo = File(output, "matrix-input-$layout-$sourceName")
                    source.copyTo(photo, overwrite = true); owned += photo
                    val uri = Uri.fromFile(photo)
                    EditStore.save(context, uri, edit)
                    main { vm.smartAutoEnabled = false; vm.dismissError(); vm.openPhotos(listOf(uri)) }
                    await { vm.errorMessage != null || vm.rendered != null && !vm.busy && vm.renderTaskState.active == null }
                    check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                    check(vm.params.overscanActive == edit.overscanActive && vm.params.overscanFormat == edit.overscanFormat &&
                        vm.params.overscanMode == edit.overscanMode) { "Matrix fixture restored the wrong film canvas" }
                    val grade = EditorViewModel::class.java.getDeclaredMethod("grade", EditorParams::class.java)
                        .apply { isAccessible = true }.invoke(vm, edit) as Grade
                    for (size in listOf(2048, 2560, 3840, 0)) {
                        val prefix = "matrix-${source.extension}-$layout-$size"
                        for (color in listOf("sRGB", "Display P3")) {
                            EngineClient.create(context).use { engine ->
                                ExportSourceDecoder.open(context, engine, uri, raw, edit,
                                    ExportOptions("TIFF", size, color, 95, false, false, true)) {}
                                engine.renderForExport().use { frame ->
                                    File(output, "$prefix-${if (color == "sRGB") "srgb" else "p3"}-reference.tiff").outputStream().use {
                                        TiffWriter.writeNative(frame, grade, null, it, {})
                                    }
                                    if (color == "sRGB") File(output, "$prefix-reference.png").outputStream().use {
                                        PngRowWriter.write(frame, grade, it, {})
                                    }
                                    if (color == "sRGB") File(output, "$prefix-reference.jpeg").outputStream().use {
                                        JpegRowWriter.write(context, frame, grade, 95, it, {})
                                    }
                                }
                            }
                        }
                        ExportRenderCache.get(context).clearDisposable()
                        for (format in listOf("JPEG", "PNG", "TIFF", "TIFF-P3")) {
                            val cache = ExportRenderCache.get(context)
                            val hits = cache.stats.hits
                            val start = SystemClock.elapsedRealtime()
                            main {
                                vm.dismissError(); vm.exportFormat = format.substringBefore('-'); vm.exportRes = size
                                vm.exportColorSpace = if (format == "TIFF-P3") "Display P3" else "sRGB"
                                vm.jpegQuality = 95; vm.exportKeepCapture = true; vm.exportKeepLocation = false; vm.export()
                            }
                            await { !vm.isExporting && !vm.busy }
                            check(vm.errorMessage == null) { "$prefix/$format: ${vm.errorMessage}" }
                            val elapsed = SystemClock.elapsedRealtime() - start
                            val saved = Uri.parse(vm.log.substringAfterLast("相册: ").lineSequence().first().trim())
                            check(saved.scheme == "content")
                            val file = File(output, "$prefix.${format.lowercase()}")
                            try { context.contentResolver.openInputStream(saved)!!.use { input -> file.outputStream().use { input.copyTo(it) } } }
                            finally { context.contentResolver.delete(saved, null, null) }
                            rows.put(JSONObject().put("input", sourceName).put("raw", raw).put("layout", layout)
                                .put("size", size).put("format", format).put("file", file.name)
                                .put("reference", if (format.startsWith("TIFF")) "$prefix-${if (format == "TIFF-P3") "p3" else "srgb"}-reference.tiff" else "$prefix-reference.${if (format == "JPEG") "jpeg" else "png"}")
                                .put("elapsed_ms", elapsed).put("cache_hit", cache.stats.hits > hits))
                            File(output, "matrix-$part.json").writeText(rows.toString(2))
                        }
                    }
                }
            }
        } finally { main { activity.finish() }; owned.forEach { it.delete() } }
        return "${rows.length()} formal exports, 4 resolutions, JPEG/PNG/TIFF sRGB/TIFF P3, part=$part; independent raster files retained for host verification"
    }
}
