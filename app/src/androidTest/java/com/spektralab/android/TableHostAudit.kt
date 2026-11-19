package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.geometry.FinishedCrop
import com.spektralab.android.core.model.*
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.export.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import com.spektralab.android.engine.host.RenderHostLease
import com.spektralab.android.feature.editor.EditorViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Independent full-canvas grade/cut references and real host/cache/cancel ownership. */
internal class TableHostAudit(private val test: Instrumentation, private val output: File) {
    private val context = test.targetContext

    private fun compare(actual: Bitmap, expected: Bitmap): Int {
        check(actual.width == expected.width && actual.height == expected.height)
        val a = IntArray(actual.width); val b = IntArray(actual.width)
        var maximum = 0
        for (y in 0 until actual.height) {
            actual.getPixels(a, 0, actual.width, 0, y, actual.width, 1)
            expected.getPixels(b, 0, expected.width, 0, y, expected.width, 1)
            for (x in a.indices) for (shift in listOf(0, 8, 16, 24))
                maximum = maxOf(maximum, abs(((a[x] ushr shift) and 255) - ((b[x] ushr shift) and 255)))
        }
        check(maximum <= 1) { "Independent table input differs by $maximum/255" }
        return maximum
    }

    private fun reference(file: File, params: EditorParams, options: ExportOptions): Bitmap =
        EngineClient.create(context).use { engine ->
            ExportSourceDecoder.open(context, engine, Uri.fromFile(file), file.extension == "dng", params, options) {}
            engine.renderExportOnce().use { pixels ->
                val values = ShortArray(pixels.width * pixels.height * 4)
                pixels.readRows(0, pixels.height, values)
                val full = ColorPipeline.applyGradeToBitmap(values, pixels.width, pixels.height, EditorGrade.from(params)).first
                val crop = params.finishedCrop.pixels(pixels.width, pixels.height)
                Bitmap.createBitmap(full, crop.left, crop.top, crop.width, crop.height)
                    .also { if (it !== full) full.recycle() }
            }
        }

    private fun await(ready: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!ready()) { check(SystemClock.elapsedRealtime() - start < 180000); Thread.sleep(40) }
    }

    fun run(): String = runBlocking {
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await { vm.engine != null && !vm.busy }
        test.runOnMainSync { vm.engine!!.park() }
        val fixture = Bitmap.createBitmap(601, 403, Bitmap.Config.ARGB_8888)
        fixture.setPixels(IntArray(601 * 403) { i -> 0xff000000.toInt() or
            ((20 + i % 601 * 210 / 601) shl 16) or ((30 + i / 601 * 190 / 403) shl 8) or ((i * 17) and 255) },
            0, 601, 0, 0, 601, 403)
        val files = listOf("table-host.png" to Bitmap.CompressFormat.PNG, "table-host.jpg" to Bitmap.CompressFormat.JPEG)
            .map { (name, format) -> File(output, name).also { file ->
                file.outputStream().use { check(fixture.compress(format, 95, it)) }
            } }.toMutableList()
        fixture.recycle()
        ExifInterface(files[1]).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        files += File(output, "table-host.dng").also { file ->
            test.context.assets.open("audit.dng").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val cache = ExportRenderCache.get(context)
        val budget = cache.storage().budget
        cache.setBudget(128L * 1024 * 1024); cache.clearDisposable()
        val journal = ExportJobStore(context).entries().map { it.id }.toSet()
        val renderer = TablePhotoRenderer(context)
        var lease: RenderHostLease? = null
        var pixels = 0L; var maxDifference = 0
        try {
            for (file in files) {
                val params = EditorParams().apply {
                    grainActive = false; glareActive = false; halationActive = false
                    quarterTurns = 1; flipH = true; exposureEv = .35f
                    finishedCrop = FinishedCrop(.1f, .05f, .7f, .85f)
                    brightness = 17f; vignetteAmount = 63f
                }
                val uri = Uri.fromFile(file)
                EditStore.save(context, uri, params)
                val source = EditStore.tableSource(context, TableSource(uri.toString(), if (file.extension == "dng") "Frame 3.jpg" else "Frame.nef", appearance = TableAppearance.EDITED))
                val request = TableExportRequest(LightTableScene(listOf(TablePlacement(UUID.randomUUID().toString(),
                    source, 0f, 0f, 100f, 100f))), TableExportOptions(longEdge = 1600), null)
                val input = TableExportSession.prepare(context, request, {}, { _, _ -> }).use {
                    it.inputs.single().copy(requestedEdge = 317)
                }
                val options = renderer.options(input, true)
                check(options.resolution == 453 && options.colorSpace == "sRGB" && options.customSize == null)
                val expected = reference(file, params, options)
                test.runOnMainSync { lease = RenderHostLease.begin(context) }
                try {
                    var parked = false
                    val actual = renderer.render(input, true, {}, { parked = true })
                    try {
                        check(parked && RenderHostLease.active)
                        maxDifference = maxOf(maxDifference, compare(actual, expected))
                        pixels += actual.width.toLong() * actual.height
                        val hits = cache.stats.hits
                        val cached = renderer.render(input, true, {}, { error("Deterministic table cache missed") })
                        try { check(cached.sameAs(actual)); check(cache.stats.hits == hits + 1) }
                        finally { cached.recycle() }
                    } finally { actual.recycle() }
                    val cancel = AtomicBoolean()
                    val changed = input.copy(params = EditSnapshot.capture(params.copy().apply { exposureEv = .44f }))
                    val failure = runCatching { renderer.render(changed, true,
                        { if (cancel.get()) throw CancellationException("Table host cancelled") }, {},
                        { stage, _ -> if (stage == "rendering") cancel.set(true) }) }.exceptionOrNull()
                    check(cancel.get() && failure?.message.orEmpty().contains("cancel", true)) { failure.toString() }
                    check(runCatching { renderer.options(input.copy(requestedEdge = 4097), true) }
                        .exceptionOrNull()?.message == "TABLE_EXPORT_CAPACITY")
                    if (file.extension == "png") {
                        // A valid cache file can still exceed a consumer's narrower capacity.
                        // Cropping it first would hide the 4097px raster from that check.
                        val smallCrop = params.copy().apply { finishedCrop = FinishedCrop(0f, 0f, .02f, .02f) }
                        val limited = input.copy(params = EditSnapshot.capture(smallCrop), requestedEdge = 20)
                        val key = checkNotNull(cache.prepare(uri, smallCrop, renderer.options(limited, true), {}))
                        val oversized = object : Rgba16Source {
                            override val width = 4097
                            override val height = 2
                            override fun readRows(y: Int, rows: Int, destination: ShortArray) {
                                destination.fill(0, 0, width * rows * 4)
                                for (at in 3 until width * rows * 4 step 4) destination[at] = (-1).toShort()
                            }
                        }
                        cache.store(key, uri, oversized, {})
                        check(runCatching { renderer.render(limited, true, {}, { error("Capacity cache missed") }) }
                            .exceptionOrNull()?.message == "TABLE_EXPORT_CAPACITY")
                    }
                } finally {
                    lease?.releaseDevice(); lease?.close(); lease = null; expected.recycle()
                }
            }
            check(ExportJobStore(context).entries().map { it.id }.toSet() == journal)
            "PNG/EXIF6 JPEG/DNG: $pixels independent full-grade-then-cut pixels, max difference $maxDifference/255; exact cached frames, real host rendering cancellation, pre-crop4097px capacity and no internal export receipts passed"
        } finally {
            lease?.releaseDevice(); lease?.close()
            cache.clearDisposable(); cache.setBudget(budget)
            files.forEach(File::delete)
            test.runOnMainSync { activity.finish() }
        }
    }
}
