package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.library.EditHistoryRepository
import com.spektralab.android.data.library.SavedPhotoRepository
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Production editor failure rollback and continuous-input commit cost; no test-only app hooks. */
internal class PersistentHistoryEditorAudit(private val test: Instrumentation, private val output: File) {
    fun run(): String {
        val context = test.targetContext
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        fun main(action: () -> Unit) = test.runOnMainSync(action)
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        fun await(done: () -> Boolean) {
            val start = SystemClock.elapsedRealtime()
            while (!done()) { check(SystemClock.elapsedRealtime() - start < 60000) { vm.errorMessage.orEmpty() }; Thread.sleep(30) }
            test.waitForIdleSync()
        }
        val source = File(output, "history-editor-${System.nanoTime()}.png")
        val uri = Uri.fromFile(source)
        var repository: SavedPhotoRepository? = null
        try {
            val image = Bitmap.createBitmap(513, 385, Bitmap.Config.ARGB_8888)
            image.eraseColor(0xff578996.toInt())
            try { source.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { image.recycle() }
            EditStore.save(context, uri, EditorParams().apply { grainActive = false; glareActive = false })
            await { vm.engine != null && !vm.busy }
            main { vm.smartAutoEnabled = false; vm.openPhotos(listOf(uri)) }
            await { vm.hasPhoto && vm.lastResult != null && !vm.busy && vm.renderTaskState.active == null }
            check(vm.errorMessage == null)
            val method = EditStore::class.java.getDeclaredMethod("repository", android.content.Context::class.java).apply { isAccessible = true }
            repository = method.invoke(EditStore, context) as SavedPhotoRepository
            val before = EditStore.history(context, uri, vm.params)
            val beforePixels = vm.rendered
            repository.history.checkpoint = { if (it == EditHistoryRepository.Checkpoint.STEP_WRITTEN) error("EDITOR_DISK_SENTINEL") }
            main { vm.params.brightness = 31f; vm.scheduleGrade() }
            check(vm.params.brightness == 0f && vm.rendered === beforePixels && !vm.canUndo && !vm.canRedo)
            check(EditStore.storageError.value != null)
            repository.history.checkpoint = {}
            check(EditStore.history(context, uri, vm.params) == before)
            EditStore.dismissStorageError()
            val mainTimes = mutableListOf<Double>()
            repeat(100) { index ->
                main {
                    val started = SystemClock.elapsedRealtimeNanos()
                    vm.params.brightness = (index + 1) / 10f
                    vm.scheduleGrade()
                    mainTimes += (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                }
                Thread.sleep(16)
            }
            await { !vm.busy && vm.renderTaskState.active == null }
            val grouped = EditStore.history(context, uri, vm.params)
            check(grouped.canUndo && !grouped.canRedo && grouped.retainedStates == 2) { "Continuous gesture split into ${grouped.retainedStates} states" }
            check(vm.params.brightness == 10f && EditStore.load(context, uri)!!.brightness == 10f)
            main { vm.undo() }
            await { vm.params.brightness == 0f && vm.canRedo && vm.renderTaskState.active == null }
            val afterUndo = EditStore.history(context, uri, vm.params)
            // An independent writer advances generation. A stale UI must not overwrite it.
            EditStore.save(context, uri, vm.params.copy().apply { saturation = 11f })
            main { vm.params.brightness = 21f; vm.scheduleGrade() }
            check(vm.params.brightness == 0f && EditStore.load(context, uri)!!.saturation == 11f)
            check(EditStore.storageError.value != null)
            check(EditStore.history(context, uri, vm.params).generation > afterUndo.generation)
            EditStore.dismissStorageError()
            val sorted = mainTimes.sorted()
            File(output, "history-performance.json").writeText(JSONObject()
                .put("device", "API37 x86_64 emulator").put("samples", mainTimes.size)
                .put("main_commit_ms", JSONArray(mainTimes)).put("median_ms", sorted[sorted.size / 2])
                .put("p95_ms", sorted[(sorted.size * .95).toInt()]).put("max_ms", sorted.last())
                .put("retained_states", grouped.retainedStates).toString(2))
            return "Editor failure restores committed parameters/buttons/pixels; 100 continuous grade updates form one undo; stale-generation writer rejected; main commit median=${sorted[50]}ms p95=${sorted[95]}ms (emulator only)"
        } finally {
            repository?.history?.checkpoint = {}
            EditStore.dismissStorageError()
            main { activity.finish() }
            source.delete()
        }
    }
}
