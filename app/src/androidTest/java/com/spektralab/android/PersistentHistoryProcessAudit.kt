package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import android.view.accessibility.AccessibilityNodeInfo

/** Separate instrumentation invocations are separated by real process death by the host runner. */
internal class PersistentHistoryProcessAudit(private val test: Instrumentation, private val output: File) {
    fun run(phase: String): String {
        val context = test.targetContext
        val record = File(output, "history-process.json")
        val source = if (phase == "seed") File(output, "history-process-${System.nanoTime()}.png")
            else File(JSONObject(record.readText()).getString("path"))
        if (phase == "seed") {
            val image = Bitmap.createBitmap(513, 385, Bitmap.Config.ARGB_8888)
            image.setPixels(IntArray(513 * 385) { i -> 0xff000000.toInt() or
                ((i % 513 * 255 / 512) shl 16) or ((i / 513 * 255 / 384) shl 8) or 96 }, 0, 513, 0, 0, 513, 385)
            try { source.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { image.recycle() }
            EditStore.save(context, Uri.fromFile(source), EditorParams().apply { grainActive = false; glareActive = false })
        }
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        fun main(action: () -> Unit) = test.runOnMainSync(action)
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        fun await(ready: () -> Boolean) {
            val start = SystemClock.elapsedRealtime()
            while (!ready()) { check(SystemClock.elapsedRealtime() - start < 60000) { "History process timeout: ${vm.errorMessage}" }; Thread.sleep(40) }
            test.waitForIdleSync()
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
        }
        fun change(action: () -> Unit) {
            val before = vm.lastResult
            main(action)
            await { vm.errorMessage != null || vm.lastResult !== before && !vm.busy && vm.renderTaskState.active == null }
        }
        fun previewHash(): String {
            val result = checkNotNull(vm.lastResult)
            val bytes = ByteBuffer.allocate(result.rgba16.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(result.rgba16)
            return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun exports(): JSONObject {
            val hashes = JSONObject()
            for (format in listOf("JPEG", "PNG", "TIFF")) {
                main {
                    vm.exportFormat = format; vm.exportRes = 0; vm.exportCustomSize = null
                    vm.exportKeepCapture = false; vm.exportKeepLocation = false; vm.export()
                }
                await { vm.errorMessage != null || !vm.isExporting && !vm.busy }
                val uri = Uri.parse(vm.log.substringAfterLast("相册: ").lineSequence().first().trim())
                check(uri.scheme == "content")
                try {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    hashes.put(format, MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it.toInt() and 255) })
                } finally { context.contentResolver.delete(uri, null, null) }
            }
            return hashes
        }
        fun click(label: String, required: Boolean = true): Boolean {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
                    var action: AccessibilityNodeInfo? = node
                    while (action != null) { if (action.isClickable) return action; action = action.parent }
                }
                for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                return null
            }
            test.uiAutomation.clearCache()
            var node = find(test.uiAutomation.rootInActiveWindow)
            if (node == null && !required) return false
            if (node != null && !node.isVisibleToUser) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                test.waitForIdleSync(); Thread.sleep(150)
                test.uiAutomation.clearCache(); node = find(test.uiAutomation.rootInActiveWindow)
            }
            val started = SystemClock.elapsedRealtime()
            while ((node == null || !node.isEnabled || !node.isVisibleToUser) && SystemClock.elapsedRealtime() - started < 8000) {
                Thread.sleep(100); test.uiAutomation.clearCache(); node = find(test.uiAutomation.rootInActiveWindow)
            }
            if (node == null || !node.isEnabled) {
                val tree = StringBuilder()
                fun describe(item: AccessibilityNodeInfo?) {
                    if (item == null) return
                    tree.append("${item.className}: ${item.text} / ${item.contentDescription} click=${item.isClickable} enabled=${item.isEnabled}\n")
                    for (index in 0 until item.childCount) describe(item.getChild(index))
                }
                describe(test.uiAutomation.rootInActiveWindow)
                File(output, "history-toolbar-failure.txt").writeText("uri=${vm.currentUri} canUndo=${vm.canUndo} canRedo=${vm.canRedo} busy=${vm.busy} exporting=${vm.isExporting}\n" + tree)
                test.uiAutomation.takeScreenshot()?.let { image ->
                    File(output, "history-toolbar-failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                }
            }
            check(node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "History UI missing/disabled: $label" }
            test.waitForIdleSync(); Thread.sleep(250)
            return true
        }
        try {
            await { vm.engine != null && !vm.busy }
            main { vm.smartAutoEnabled = false; vm.openPhotos(listOf(Uri.fromFile(source))) }
            await { vm.errorMessage != null || vm.hasPhoto && vm.lastResult != null && !vm.busy && vm.renderTaskState.active == null }
            when (phase) {
                "seed" -> {
                    change { vm.params.exposureEv = 1f; vm.scheduleRender() }
                    Thread.sleep(420)
                    change { vm.params.exposureEv = 2f; vm.scheduleRender() }
                    check(vm.canUndo)
                    change { vm.undo() }
                    check(vm.params.exposureEv == 1f && vm.canUndo && vm.canRedo)
                    record.writeText(JSONObject().put("path", source.path).put("expected", vm.params.toJson())
                        .put("preview_sha256", previewHash()).put("exports", exports()).toString())
                }
                "verify" -> {
                    check(vm.canUndo && vm.canRedo) { "Process restart lost undo/redo cursor" }
                    check(vm.params.exposureEv == 1f)
                    val expected = JSONObject(record.readText())
                    check(previewHash() == expected.getString("preview_sha256")) { "Restart changed rendered pixels" }
                    val actual = exports()
                    for (format in listOf("JPEG", "PNG", "TIFF")) check(actual.getString(format) == expected.getJSONObject("exports").getString(format)) {
                        "Restart changed original-size $format output"
                    }
                    // Each phase starts a fresh Home activity. Export toasts can
                    // temporarily own the active window; wait for the real entry.
                    click("继续显影")
                    click("拍摄", required = false)
                    click("返回大图预览", required = false)
                    val beforeRedo = vm.lastResult
                    click("重做")
                    await { vm.lastResult !== beforeRedo && vm.renderTaskState.active == null }
                    check(vm.params.exposureEv == 2f)
                    val beforeUndo = vm.lastResult
                    click("撤销")
                    await { vm.lastResult !== beforeUndo && vm.renderTaskState.active == null }
                    check(vm.params.exposureEv == 1f)
                    test.uiAutomation.takeScreenshot()?.let { image ->
                        File(output, "history-restored-toolbar.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                    }
                    change { vm.params.exposureEv = 3f; vm.scheduleRender() }
                    check(!vm.canRedo) { "New branch retained redo" }
                    record.writeText(JSONObject(record.readText()).put("branch_expected", vm.params.toJson()).toString())
                }
                "branch" -> {
                    check(vm.canUndo && !vm.canRedo && vm.params.exposureEv == 3f)
                    change { vm.undo() }; check(vm.params.exposureEv == 1f)
                    change { vm.redo() }; check(vm.params.exposureEv == 3f)
                }
                else -> error("Unknown history phase")
            }
            return "phase=$phase; real restart retains cursor/redo; exact RGBA16 preview and JPEG/PNG/TIFF original-size hashes; new branch persists"
        } finally { main { activity.finish() } }
    }
}
