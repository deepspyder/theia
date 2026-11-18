package com.spektralab.android

import android.app.Instrumentation
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File

internal class ExportOptionsBatchAudit(private val test: Instrumentation) {
    fun run(): String {
        val context = test.targetContext
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        fun main(action: () -> Unit) = test.runOnMainSync(action)
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        fun await(done: () -> Boolean) {
            val start = SystemClock.elapsedRealtime()
            while (!done()) { check(SystemClock.elapsedRealtime() - start < 60000) { "Batch timeout: ${vm.errorMessage}" }; Thread.sleep(40) }
            test.waitForIdleSync()
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = context.contentResolver
        fun media(): Set<Long> = buildSet {
            resolver.query(collection, arrayOf(MediaStore.Images.Media._ID), null, null, null)!!.use {
                while (it.moveToNext()) add(it.getLong(0))
            }
        }
        val directory = File(context.cacheDir, "r02-batch-${System.nanoTime()}").apply { mkdirs() }
        val basename = "R02_batch_${System.nanoTime()}"
        val before = media()
        try {
            await { vm.engine != null && !vm.busy }
            val sources = (0..1).map { index ->
                val dir = File(directory, "$index").apply { mkdirs() }
                val file = File(dir, "$basename.png")
                val image = Bitmap.createBitmap(if (index == 0) 301 else 203, if (index == 0) 203 else 301, Bitmap.Config.ARGB_8888)
                try {
                    image.eraseColor(if (index == 0) 0xff487896.toInt() else 0xff967848.toInt())
                    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { image.recycle() }
                Uri.fromFile(file).also { uri -> EditStore.save(context, uri, EditorParams().apply { grainActive = false; glareActive = false }) }
            }
            main { vm.smartAutoEnabled = false; vm.dismissError(); vm.openPhotos(sources) }
            await { vm.hasPhoto && !vm.busy && vm.renderTaskState.active == null }
            main {
                vm.exportFormat = "PNG"; vm.exportRes = 2560
                vm.exportCustomSize = ExportSize(ExportSize.Kind.LONG_EDGE, 127.0)
                vm.exportNaming = ExportNaming(listOf(ExportNaming.Token.ORIGINAL))
                vm.exportKeepCapture = false; vm.exportKeepLocation = false
                vm.batchExport()
                vm.exportCustomSize = ExportSize(ExportSize.Kind.LONG_EDGE, 1.0)
                vm.exportNaming = ExportNaming(listOf(ExportNaming.Token.DATE))
            }
            await { !vm.isBatchExporting && !vm.busy }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            val outputs = media() - before
            check(outputs.size == 2) { "Expected two batch outputs, got ${outputs.size}" }
            val names = mutableSetOf<String>()
            val dimensions = mutableSetOf<Pair<Int, Int>>()
            for (id in outputs) {
                val uri = ContentUris.withAppendedId(collection, id)
                resolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)!!.use {
                    check(it.moveToFirst()); names += it.getString(0)
                }
                val image = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }!!
                try { dimensions += image.width to image.height } finally { image.recycle() }
            }
            check(names == setOf("$basename.png", "$basename (1).png")) { "Names not frozen/deduplicated: $names" }
            check(dimensions == setOf(127 to 86, 86 to 127)) { "Dimensions not frozen: $dimensions" }
            val afterBatch = media()
            main {
                vm.exportCustomSize = ExportSize(ExportSize.Kind.SHORT_EDGE, 79.0)
                vm.softProof()
            }
            await { !vm.isExporting && !vm.busy }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            check(vm.lastResult?.width == 117 && vm.lastResult?.height == 79) { "Custom soft proof ignored final sizing" }
            check(media() == afterBatch) { "Soft proof published a file" }
            main {
                vm.exportCustomSize = ExportSize(ExportSize.Kind.SHORT_EDGE, 79.0)
                vm.export(); vm.cancelRender()
            }
            await { !vm.isExporting && !vm.busy }
            check(media() == afterBatch) { "Cancelled custom export left an output" }
            return "Two same-name batch sources retain frozen 127px limits and per-source aspect; unique names preserved; custom soft proof respects finished size without publishing; cancelled custom export publishes nothing"
        } finally {
            (media() - before).forEach { resolver.delete(ContentUris.withAppendedId(collection, it), null, null) }
            main { vm.exportCustomSize = null; vm.exportNaming = ExportNaming(); activity.finish() }
            directory.deleteRecursively()
        }
    }
}
