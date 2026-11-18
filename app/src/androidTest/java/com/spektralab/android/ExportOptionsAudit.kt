package com.spektralab.android

import android.app.Instrumentation
import android.content.ContextWrapper
import android.provider.MediaStore
import android.net.Uri
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.data.local.RecipeStore
import com.spektralab.android.engine.Rgba16Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** Independent area-integration, persistence, IPC and real publication contracts for R02. */
internal class ExportOptionsAudit(private val test: Instrumentation) {
    fun run(): String {
        sizesAndPixels()
        recipes()
        namingAndPublication()
        return "R02: final-raster limits/no-upscale, 16-bit independent area integration, cancellation, v1/v2/v3 IPC, legacy/unknown/corrupt recipe safety, Unicode naming and real collision/failure publication passed"
    }

    private fun sizesAndPixels() {
        fun size(kind: ExportSize.Kind, amount: Double) = ExportSize(kind, amount)
        check(size(ExportSize.Kind.LONG_EDGE, 2048.0).dimensions(6064, 4040) == (2048 to 1364))
        check(size(ExportSize.Kind.SHORT_EDGE, 1080.0).dimensions(6064, 4040) == (1621 to 1080))
        check(size(ExportSize.Kind.SHORT_EDGE, 1080.0).dimensions(4040, 6064) == (1080 to 1621))
        check(size(ExportSize.Kind.LONG_EDGE, 65535.0).dimensions(7, 9) == (7 to 9))
        check(size(ExportSize.Kind.SHORT_EDGE, 1.0).dimensions(65535, 1) == (65535 to 1))
        check(size(ExportSize.Kind.LONG_EDGE, 1.0).dimensions(65535, 1) == (1 to 1))
        for (kind in ExportSize.Kind.entries) for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0))
            check(runCatching { size(kind, invalid) }.isFailure)
        check(runCatching { size(ExportSize.Kind.LONG_EDGE, 1.2) }.isFailure)
        check(runCatching { size(ExportSize.Kind.SHORT_EDGE, 65536.0) }.isFailure)
        check(runCatching { size(ExportSize.Kind.MEGAPIXELS, 200.1) }.isFailure)
        val mp = size(ExportSize.Kind.MEGAPIXELS, 12.0).dimensions(6064, 4040)
        check(abs(mp.first.toLong() * mp.second - 12_000_000) < 7000)
        val options = ExportOptions("TIFF", 2560, "Display P3", 95, true, false, true,
            size(ExportSize.Kind.SHORT_EDGE, 1080.0), ExportNaming(listOf(ExportNaming.Token.PAPER, ExportNaming.Token.ORIGINAL), "-"))
        val request = ExportFrameRequest(UUID.randomUUID().toString(), "0".repeat(64), "photo.dng", true,
            EditSnapshot.capture(EditorParams()), options, "decoder", "renderer")
        check(ExportFrameRequest.fromJson(request.toJson()) == request)
        for (key in listOf("custom_size", "naming")) {
            val invalid = JSONObject(request.toJson())
            invalid.getJSONObject("options").put(key, "not-an-object")
            check(runCatching { ExportFrameRequest.fromJson(invalid.toString()) }.isFailure)
        }
        for (schema in 1..2) {
            val old = JSONObject(request.toJson()).put("schema", schema)
            old.getJSONObject("options").remove("custom_size")
            old.getJSONObject("options").remove("naming")
            val restored = ExportFrameRequest.fromJson(old.toString())
            check(restored.options.customSize == null && restored.options.naming.tokens.isEmpty() && restored.options.renderResolution == 2560)
        }
        check(options.renderResolution == 0)
        for ((width, height) in listOf(17 to 11, 11 to 17, 1 to 23, 29 to 1, 31 to 19)) {
            val pixels = ShortArray(width * height * 4) { ((it * 19357L + 32771) % 65536).toShort() }
            val source = object : Rgba16Source {
                override val width = width
                override val height = height
                override fun readRows(y: Int, rows: Int, destination: ShortArray) {
                    check(rows == 1) { "Resizer read a whole raster" }
                    pixels.copyInto(destination, 0, y * width * 4, (y + rows) * width * 4)
                }
            }
            check(SizedRgba16Source.wrap(source, null) {} === source)
            check(SizedRgba16Source.wrap(source, size(ExportSize.Kind.LONG_EDGE, 65535.0)) {} === source)
            for (edge in listOf(1, 3, 7)) {
                val target = SizedRgba16Source.wrap(source, size(ExportSize.Kind.LONG_EDGE, edge.toDouble())) {}
                val result = ShortArray(target.width * target.height * 4)
                if (target === source) pixels.copyInto(result) else target.readRows(0, target.height, result)
                // Deliberately integrate all source pixels for each destination footprint;
                // independent of production's separable/cache/strip traversal.
                for (y in 0 until target.height) for (x in 0 until target.width) for (c in 0..3) {
                    var value = 0.0
                    val left = x.toDouble() * width / target.width
                    val right = (x + 1.0) * width / target.width
                    val top = y.toDouble() * height / target.height
                    val bottom = (y + 1.0) * height / target.height
                    for (sy in 0 until height) for (sx in 0 until width) {
                        val area = (minOf(right, sx + 1.0) - maxOf(left, sx.toDouble())).coerceAtLeast(0.0) *
                            (minOf(bottom, sy + 1.0) - maxOf(top, sy.toDouble())).coerceAtLeast(0.0)
                        value += (pixels[(sy * width + sx) * 4 + c].toInt() and 65535) * area
                    }
                    val expected = (value / ((right - left) * (bottom - top))).roundToInt()
                    check(abs((result[(y * target.width + x) * 4 + c].toInt() and 65535) - expected) <= 1)
                }
                if (target !== source) {
                    val again = ShortArray(target.width * 4)
                    target.readRows(target.height - 1, 1, again)
                    check(again.contentEquals(result.copyOfRange(result.size - again.size, result.size)))
                }
            }
            val cancelled = SizedRgba16Source.wrap(source, size(ExportSize.Kind.LONG_EDGE, 1.0)) { error("cancel-area") }
            if (cancelled !== source) check(runCatching { cancelled.readRows(0, 1, ShortArray(4)) }.exceptionOrNull()?.message == "cancel-area")
        }
    }

    private fun recipes() {
        val directory = File(test.targetContext.cacheDir, "r02-recipes-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = directory }
        val file = File(directory, "recipes.json")
        try {
            check(RecipeStore.load(context) == RecipeStore.DEFAULTS)
            val legacy = JSONObject().put("name", "Legacy").put("format", "PNG").put("res", 2048)
                .put("color_space", "sRGB").put("unowned", JSONObject().put("keep", 7))
            file.writeText(JSONArray().put(legacy).toString())
            val old = RecipeStore.load(context).single()
            check(old.customSize == null && old.naming.tokens.isEmpty())
            val updated = old.copy(name = "New", customSize = ExportSize(ExportSize.Kind.MEGAPIXELS, 1.25),
                naming = ExportNaming(listOf(ExportNaming.Token.DATE, ExportNaming.Token.ORIGINAL), " "))
            RecipeStore.save(context, listOf(updated), mapOf("New" to "Legacy"))
            check(RecipeStore.load(context) == listOf(updated))
            check(JSONArray(file.readText()).getJSONObject(0).getJSONObject("unowned").getInt("keep") == 7)
            val json = JSONArray(file.readText())
            json.getJSONObject(0).getJSONObject("naming").put("unowned-naming", 9)
            file.writeText(json.toString())
            RecipeStore.save(context, listOf(updated))
            check(JSONArray(file.readText()).getJSONObject(0).getJSONObject("naming").getInt("unowned-naming") == 9)
            val before = file.readText()
            check(runCatching { RecipeStore.save(context, listOf(updated, updated.copy(name = "new"))) }.isFailure)
            check(file.readText() == before)
            for (invalid in listOf("{broken", JSONArray().put(legacy.put("version", 99)).toString())) {
                file.writeText(invalid)
                check(RecipeStore.readError(context))
                check(runCatching { RecipeStore.save(context, RecipeStore.DEFAULTS) }.isFailure)
                check(file.readText() == invalid) { "Unreadable recipe overwritten" }
            }
            file.writeText(before)
            RecipeStore.save(context, emptyList())
            check(RecipeStore.load(context).isEmpty()) { "Deleted recipes resurrected" }
            // AtomicFile recovers a pre-existing legacy backup.
            File(directory, "recipes.json.bak").writeText(before)
            file.writeText("partial")
            check(RecipeStore.load(context) == listOf(updated))
        } finally { directory.deleteRecursively() }
    }

    private fun namingAndPublication() {
        check(ExportNaming().stem("x.nef", "film", "paper", "2026-11-18", "42") == "Theia_42")
        check(ExportNaming(listOf(ExportNaming.Token.PAPER, ExportNaming.Token.ORIGINAL), "-")
            .stem("DSC.001.NEF", "f", "Paper", "d", "s") == "Paper-DSC.001")
        val hostile = ExportNaming.safePart("../a\\b/:\u0000\u202E" + "照片😀".repeat(200))
        check('/' !in hostile && '\\' !in hostile && ':' !in hostile && hostile.toByteArray().size <= 180)
        check(hostile.none(Char::isISOControl) && !hostile.endsWith('.'))
        check(runCatching { ExportNaming(listOf(ExportNaming.Token.ORIGINAL, ExportNaming.Token.ORIGINAL)) }.isFailure)
        check(ExportPublisher.uniqueName("test.png") { it in setOf("test.png", "test (1).png") } == "test (2).png")
        val resolver = test.targetContext.contentResolver
        val outputs = mutableListOf<Uri>()
        val name = "Theia_R02_${System.nanoTime()}.png"
        val publisher = ExportPublisher(test.targetContext, {}, {})
        fun display(uri: Uri) = resolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)!!.use {
            check(it.moveToFirst()); it.getString(0)
        }
        try {
            repeat(2) { index ->
                outputs += Uri.parse(publisher.write(name, "image/png") { it.write(byteArrayOf(index.toByte())) }.removePrefix("相册: "))
            }
            check(display(outputs[0]) != display(outputs[1])) { "Repeated export reused a filename" }
            check(resolver.openInputStream(outputs[0])!!.use { it.read() } == 0) { "Existing output overwritten" }
            check(resolver.openInputStream(outputs[1])!!.use { it.read() } == 1)
            var pending: Uri? = null
            publisher.onPrepared = { pending = Uri.parse(it) }
            check(runCatching { publisher.write(name, "image/png") { error("encode-r02") } }.exceptionOrNull()?.message == "encode-r02")
            check(resolver.query(pending!!, arrayOf(MediaStore.Images.Media._ID), null, null, null)!!.use { !it.moveToFirst() })
        } finally { outputs.forEach { resolver.delete(it, null, null) } }
        val directory = File(test.targetContext.cacheDir, "r02-file-publish-${System.nanoTime()}").apply { mkdirs() }
        try {
            val local = ExportPublisher(test.targetContext, {}, {})
            val first = local.writeFile(directory, "same.png") { it.write(byteArrayOf(1)) }
            val second = local.writeFile(directory, "same.png") { it.write(byteArrayOf(2)) }
            check(first != second && File(first).readBytes().contentEquals(byteArrayOf(1)))
            check(runCatching { local.writeFile(directory, "fail.png") { error("local-write") } }.exceptionOrNull()?.message == "local-write")
            check(directory.listFiles()!!.none { it.name.endsWith(".pending") || it.name == "fail.png" })
            local.onReadyToCommit = { File(directory, "race.png").writeText("preserve") }
            check(runCatching { local.writeFile(directory, "race.png") { it.write(9) } }.isFailure)
            check(File(directory, "race.png").readText() == "preserve") { "Late output overwritten" }
        } finally { directory.deleteRecursively() }
    }
}
