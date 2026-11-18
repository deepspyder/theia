package com.spektralab.android

import android.app.Instrumentation
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.color.NeutralWhiteBalance
import com.spektralab.android.core.model.EditSnapshot
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.SpkResult
import com.spektralab.android.engine.RawDecoder
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.NeutralPickController
import com.spektralab.android.feature.editor.ui.NeutralPickView
import com.spektralab.android.feature.editor.ui.panels.NeutralPickButton
import com.spektralab.android.feature.editor.ui.panels.RawWhiteBalanceOptions
import com.spektralab.android.ui.theme.TheiaTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Stage pixels, actual Material controls, editor persistence and source-boundary checks. */
internal class NeutralPickAudit(private val test: Instrumentation, private val output: File) {
    private val context = test.targetContext
    private val timings = JSONArray()
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun await(label: String, predicate: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() - start < 90000) { "Neutral timeout: $label" }
            Thread.sleep(40)
        }
        test.waitForIdleSync()
    }
    private fun idle(vm: EditorViewModel) {
        await("editor: ${vm.errorMessage}") { vm.errorMessage != null || vm.hasPhoto && !vm.busy &&
            vm.lastResult != null && vm.renderTaskState.active == null && !vm.isExporting && !vm.isBatchExporting && vm.canPickNeutral }
        check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
        // Grade-only requests publish after the native task is already idle.
        Thread.sleep(300); test.waitForIdleSync()
    }
    private fun ready(controller: NeutralPickController) {
        await("stage") { controller.state?.working == false }
        check(controller.state!!.image != null) { controller.state!!.note.orEmpty() }
    }
    private fun root(): AccessibilityNodeInfo? {
        test.uiAutomation.clearCache(); return test.uiAutomation.rootInActiveWindow
    }
    private fun find(label: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(label, node.getChild(i))?.let { return it }
        return null
    }
    private fun click(label: String) {
        await(label) { find(label)?.isVisibleToUser == true }
        fun action(item: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (item == null) return null
            if (item.text?.toString() == label || item.contentDescription?.toString() == label) {
                var parent: AccessibilityNodeInfo? = item
                while (parent != null) { if (parent.isClickable) return parent; parent = parent.parent }
            }
            for (index in 0 until item.childCount) action(item.getChild(index))?.let { return it }
            return null
        }
        val node = checkNotNull(action(root())) { "No clickable control: $label" }
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
        test.waitForIdleSync(); Thread.sleep(150)
    }
    private fun capture(label: String) {
        test.waitForIdleSync(); Thread.sleep(200)
        val image = checkNotNull(test.uiAutomation.takeScreenshot())
        try { File(output, "neutral-$label.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }
        finally { image.recycle() }
    }
    private fun tapPhoto(controller: NeutralPickController, x: Float, y: Float) {
        val bounds=Rect()
        checkNotNull(find(context.getString(R.string.neutral_photo))).getBoundsInScreen(bounds)
        val image=checkNotNull(controller.state!!.image)
        val expected=NeutralWhiteBalance.imagePoint(bounds.width().toFloat(),bounds.height().toFloat(),image.width,image.height,
            bounds.width()*x,bounds.height()*y)
        val previous=controller.state!!.point
        val time=SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(time,time+if(action==MotionEvent.ACTION_UP) 60 else 0,action,
                bounds.left+bounds.width()*x,bounds.top+bounds.height()*y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            try { check(test.uiAutomation.injectInputEvent(event,true)) } finally { event.recycle() }
        }
        test.waitForIdleSync();Thread.sleep(120)
        if(expected==null) check(controller.state!!.point==previous)
        else {
            val actual=checkNotNull(controller.state!!.point)
            check(abs(actual.first-expected.first)<.002 && abs(actual.second-expected.second)<.002)
        }
    }
    private fun candidate(controller: NeutralPickController) {
        for (y in 2..8) for (x in 2..8) {
            main { controller.pick(x / 10.0, y / 10.0) }
            if (controller.state?.balance != null) return
        }
        error("No measurable grey patch: ${controller.state?.note}")
    }

    fun stageBoundaries(): String {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = NeutralPickController(context, scope)
        val pixels = ShortArray(11 * 9 * 4) { i -> when(i % 4) { 0 -> 15001; 1 -> 16003; 2 -> 17011; else -> -1 } }
        val frame = SpkResult(pixels,11,9,11,0.0,false,false,"")
        var current = true; var commits = 0
        val edit = EditSnapshot.capture(EditorParams())
        fun open() {
            main { controller.open(NeutralWhiteBalance.Target.PRINT,null,edit,frame,"Display P3",{ current }) { commits++ } }
            ready(controller)
        }
        try {
            open(); main { controller.pick(.5,.5) }
            val actual = checkNotNull(controller.state!!.balance)
            val expected = checkNotNull(NeutralWhiteBalance.printBalance(doubleArrayOf(15001.0,16003.0,17011.0).map { it/65535 }.toDoubleArray()))
            check(abs(actual.temperature-expected.temperature)<1e-12 && abs(actual.tint-expected.tint)<1e-12)
            main { controller.close() }; check(commits==0)
            for (value in listOf(0,65535)) {
                for (i in pixels.indices) if(i%4!=3) pixels[i]=value.toShort()
                open(); main { controller.pick(.5,.5); controller.apply() }
                check(controller.state!!.balance==null && controller.state!!.note!=null && commits==0)
                main { controller.close() }
            }
            for(i in pixels.indices) pixels[i]=if(i%4==3) 0 else 14000
            open(); main { controller.pick(.5,.5) }; check(controller.state!!.balance==null)
            main { controller.close() }
            for(i in pixels.indices) pixels[i]=when(i%4){0->15001;1->16003;2->17011;else->-1}
            open(); main { controller.pick(.5,.5) }; current=false
            main { controller.apply() }
            await("stale apply") { controller.state?.working==false }
            check(controller.state!!.balance==null && commits==0)
            current=true; main { controller.close() }
            val raw = File(output,"neutral-mutable-${System.nanoTime()}.NEF")
            File(output,"pc-parity-DSC_5099.NEF").copyTo(raw)
            try {
                main { controller.open(NeutralWhiteBalance.Target.DECODE,Uri.fromFile(raw),edit,null,"sRGB",{true}){commits++} }
                ready(controller); candidate(controller)
                raw.appendBytes(byteArrayOf(42))
                main { controller.apply() }; await("source rejection") { controller.state?.working==false }
                check(controller.state!!.balance==null && commits==0)
                main { controller.close() }
                repeat(3) {
                    main { controller.open(NeutralWhiteBalance.Target.DECODE,Uri.fromFile(raw),edit,null,"sRGB",{true}){commits++}; controller.close() }
                }
                Thread.sleep(500); check(controller.state==null && commits==0)
            } finally { raw.delete() }
            return "Exact U16 (including sub-8bit values)/P3 stage sampling, black/clipped/transparent rejection, cancel, stale edit/apply, changed RAW bytes and loading cancellation passed"
        } finally { main { controller.close() }; scope.cancel() }
    }

    fun editor(layoutOnly: Boolean): String {
        val activity = test.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        main { vm=ViewModelProvider(activity)[EditorViewModel::class.java] }
        await("engine") { vm.engine!=null && !vm.busy }
        val previousAuto=vm.smartAutoEnabled
        main { vm.smartAutoEnabled=false }
        fun mount(raw: Boolean) = main { activity.setContent { TheiaTheme { Surface {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
                if(raw) RawWhiteBalanceOptions(vm) else NeutralPickButton(vm,NeutralWhiteBalance.Target.PRINT)
            }
            NeutralPickView(vm.neutralPick)
        } } } }
        fun open(uri: Uri) { main { vm.openPhotos(listOf(uri)) }; idle(vm) }
        fun pick(target: NeutralWhiteBalance.Target) {
            val start=SystemClock.elapsedRealtime()
            click(context.getString(if(target==NeutralWhiteBalance.Target.PRINT) R.string.neutral_pick_print else R.string.neutral_pick_decode))
            if(layoutOnly && target==NeutralWhiteBalance.Target.DECODE && vm.neutralPick.state?.working==true) capture("layout-loading")
            ready(vm.neutralPick)
            timings.put(JSONObject().put("stage",target.name).put("prepare_ms",SystemClock.elapsedRealtime()-start))
        }
        fun export(format: String, resolution: Int): Uri {
            val start = SystemClock.elapsedRealtime()
            main {
                vm.exportFormat = format; vm.exportRes = resolution; vm.exportCustomSize = null
                vm.exportKeepCapture = false; vm.exportKeepLocation = false; vm.export()
                check(vm.isExporting)
            }
            await("export $format/$resolution") { vm.errorMessage != null || !vm.isExporting }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            timings.put(JSONObject().put("format",format).put("resolution",resolution)
                .put("export_ms",SystemClock.elapsedRealtime()-start))
            return Uri.parse(vm.log.substringAfterLast("相册: ").lineSequence().first().trim()).also {
                check(it.scheme == "content") { vm.log }
            }
        }
        fun pngSize(uri: Uri): Pair<Int,Int> {
            val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,bounds) }
            check(bounds.outWidth > 0 && bounds.outHeight > 0)
            return bounds.outWidth to bounds.outHeight
        }
        try {
            val formats=if(layoutOnly) listOf("PNG") else listOf("JPEG","PNG","WEBP")
            for(format in formats) {
                val file=File(output,"neutral-input-${System.nanoTime()}.$format")
                val image=Bitmap.createBitmap(601,403,Bitmap.Config.ARGB_8888)
                image.setPixels(IntArray(601*403){ i->
                    val level=70+(i%601)*90/601
                    0xff000000.toInt() or (level shl 16) or ((level+5) shl 8) or (level+8)
                },0,601,0,0,601,403)
                file.outputStream().use { image.compress(when(format){"JPEG"->Bitmap.CompressFormat.JPEG;"PNG"->Bitmap.CompressFormat.PNG;else->Bitmap.CompressFormat.WEBP_LOSSLESS},100,it) }; image.recycle()
                if(format=="JPEG") ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION,"6");saveAttributes() }
                val uri=Uri.fromFile(file)
                EditStore.save(context,uri,EditorParams().apply { grainActive=false;glareActive=false;quarterTurns=1;rotationDeg=3.5f;cropX=.1f;cropY=.15f;cropW=.8f;cropH=.7f })
                open(uri); mount(false)
                val baseline=EditSnapshot.capture(vm.params)
                pick(NeutralWhiteBalance.Target.PRINT)
                check(vm.neutralPick.state!!.image!!.width==vm.lastResult!!.width)
                tapPhoto(vm.neutralPick,.6f,.5f)
                click(context.getString(R.string.neutral_center))
                check(vm.neutralPick.state!!.balance!=null)
                capture(if(layoutOnly) "layout-print-candidate" else "$format-print-candidate")
                if(layoutOnly) {
                    click(context.getString(R.string.neutral_cancel)); pick(NeutralWhiteBalance.Target.PRINT)
                    main { vm.neutralPick.pick(0.0,0.0) }
                    capture("layout-print-edge")
                    click(context.getString(R.string.neutral_cancel))
                    break
                }
                click(context.getString(R.string.neutral_cancel)); check(EditSnapshot.capture(vm.params)==baseline)
                pick(NeutralWhiteBalance.Target.PRINT); click(context.getString(R.string.neutral_center))
                val balance=checkNotNull(vm.neutralPick.state!!.balance)
                click(context.getString(R.string.neutral_apply)); await("apply") { vm.neutralPick.state==null }; idle(vm)
                check(vm.params.temperature==balance.temperature.toFloat() && vm.params.tint==balance.tint.toFloat() && vm.canUndo)
                val edited=EditSnapshot.capture(vm.params)
                main { vm.undo() }; idle(vm); check(EditSnapshot.capture(vm.params)==baseline)
                main { vm.redo() }; idle(vm); check(EditSnapshot.capture(vm.params)==edited)
                open(uri); check(EditSnapshot.capture(vm.params)==edited)
                for(exportFormat in listOf("JPEG","PNG","TIFF")) context.contentResolver.delete(export(exportFormat,384),null,null)
            }
            val rawUri=Uri.fromFile(File(output,"pc-parity-DSC_5099.NEF"))
            EditStore.save(context,rawUri,EditorParams().apply { grainActive=false;glareActive=false })
            open(rawUri); mount(true)
            click(context.getString(R.string.neutral_raw_presets)); capture("${if(layoutOnly) "layout-" else ""}presets")
            click(context.getString(R.string.neutral_preset_daylight)); idle(vm)
            check(vm.params.cameraWbCustom && vm.params.cameraWbKelvin==5500f && vm.cameraAsShot!=null)
            click(context.getString(R.string.neutral_raw_temperature_shot)); idle(vm)
            check(vm.params.cameraWbTemperatureAsShot && !vm.params.cameraWbTintAsShot)
            val mixed = EditSnapshot.capture(vm.params)
            if (!layoutOnly) {
                val result = export("PNG", 384)
                try { check(pngSize(result).let { maxOf(it.first,it.second) == 384 }) }
                finally { context.contentResolver.delete(result,null,null) }
            }
            click(context.getString(R.string.neutral_raw_tint_shot)); idle(vm)
            check(vm.params.cameraWbTemperatureAsShot && vm.params.cameraWbTintAsShot)
            capture("${if(layoutOnly) "layout-" else ""}raw-axes")
            pick(NeutralWhiteBalance.Target.DECODE); candidate(vm.neutralPick)
            // Choose the actual letterbox side; landscape may have horizontal bars.
            val bounds=Rect()
            checkNotNull(find(context.getString(R.string.neutral_photo))).getBoundsInScreen(bounds)
            val sampleImage=checkNotNull(vm.neutralPick.state!!.image)
            if(bounds.width().toDouble()/bounds.height()>sampleImage.width.toDouble()/sampleImage.height)
                tapPhoto(vm.neutralPick,.01f,.5f) else tapPhoto(vm.neutralPick,.5f,.01f)
            capture("${if(layoutOnly) "layout-" else ""}raw-candidate")
            val before=EditSnapshot.capture(vm.params)
            if(layoutOnly) {
                click(context.getString(R.string.neutral_cancel))
                main {
                    vm.neutralPick.open(NeutralWhiteBalance.Target.DECODE,Uri.fromFile(File(output,"missing-neutral-source.NEF")),
                        EditSnapshot.capture(vm.params),null,"sRGB",{true}) { error("Unavailable source committed") }
                }
                await("unavailable source") { vm.neutralPick.state?.working==false }
                check(vm.neutralPick.state!!.image==null && vm.neutralPick.state!!.note!=null)
                capture("layout-error")
                check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                await("system Back") { vm.neutralPick.state==null }
            } else {
                click(context.getString(R.string.neutral_apply)); idle(vm)
                check(!vm.params.cameraWbTemperatureAsShot && !vm.params.cameraWbTintAsShot)
                val edited=EditSnapshot.capture(vm.params)
                main { vm.undo() }; idle(vm); check(EditSnapshot.capture(vm.params)==before)
                main { vm.redo() }; idle(vm); check(EditSnapshot.capture(vm.params)==edited)
                open(rawUri); check(EditSnapshot.capture(vm.params)==edited)
                val originalSize=RawDecoder.decodeForExport(context,rawUri,0).use { it.width to it.height }
                check(originalSize==(6064 to 4040))
                for (format in listOf("JPEG","PNG","TIFF")) {
                    val exported=export(format,0)
                    try { if(format=="PNG") check(pngSize(exported)==originalSize) { "Original export ${pngSize(exported)} differs from native source $originalSize" } }
                    finally { context.contentResolver.delete(exported,null,null) }
                }
                pick(NeutralWhiteBalance.Target.DECODE); main { vm.openPhoto(rawUri) }; idle(vm)
                check(vm.neutralPick.state==null)
                val asset = EditStore.savedPhotos(context).first { it.uri == rawUri.toString() }
                val default = EditStore.editVersions(context,asset.assetId).first()
                val variant = EditStore.createEditVersion(context,default,"R04-${System.nanoTime()}")
                try {
                    EditStore.save(context,variant.editUri,mixed.toParams())
                    open(variant.editUri)
                    check(vm.currentIsRaw && EditSnapshot.capture(vm.params)==mixed)
                    pick(NeutralWhiteBalance.Target.DECODE); candidate(vm.neutralPick)
                    click(context.getString(R.string.neutral_cancel))
                    val media = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    fun ids() = context.contentResolver.query(media,arrayOf("_id"),null,null,null)!!.use { rows ->
                        buildSet { while(rows.moveToNext()) add(rows.getLong(0)) }
                    }
                    main { vm.openPhotos(listOf(rawUri,variant.editUri)) }; idle(vm)
                    val previous = ids()
                    try {
                        main { vm.exportFormat="PNG";vm.exportRes=384;vm.batchExport();check(vm.isBatchExporting) }
                        idle(vm)
                        val added = ids() - previous
                        check(added.size==2) { "Expected default and mixed-axis variant outputs: $added" }
                        val files = added.map { ContentUris.withAppendedId(media,it) }
                        check(files.all { pngSize(it).let { size -> maxOf(size.first,size.second)==384 } })
                        val bytes = files.map { context.contentResolver.openInputStream(it)!!.use { stream -> stream.readBytes() } }
                        check(!bytes[0].contentEquals(bytes[1]))
                    } finally { (ids()-previous).forEach { context.contentResolver.delete(ContentUris.withAppendedId(media,it),null,null) } }
                    check(EditSnapshot.capture(EditStore.load(context,rawUri)!!)==edited)
                } finally { EditStore.deleteEditVersion(context,EditStore.editVersion(context,variant.id)) }
                // Leave a mixed-axis state and redo branch for the next instrumentation process.
                open(rawUri)
                main { vm.params.cameraWbTemperatureAsShot=true;vm.params.cameraWbTintAsShot=false;vm.scheduleCameraWb() }; idle(vm)
                val persisted=EditSnapshot.capture(vm.params)
                main { vm.undo() }; idle(vm)
                check(EditSnapshot.capture(vm.params)==edited && vm.canRedo)
                File(output,"neutral-process.json").writeText(JSONObject().put("uri",rawUri.toString())
                    .put("current",edited.json).put("redo",persisted.json).toString())
            }
            File(output,"neutral-timings.json").writeText(timings.toString(2))
            return if(layoutOnly) "Native picker/edge/candidate, dropdown and two as-shot switches captured at current device configuration"
                else "JPEG/EXIF6, PNG, WebP cropped/rotated print samples and nine exports; NEF preset/axis/sample history, mixed-axis export, virtual copy sampling and two distinct batch PNGs passed"
        } finally { main { vm.neutralPick.close();vm.smartAutoEnabled=previousAuto;activity.finish() } }
    }

    fun process(): String {
        val record=JSONObject(File(output,"neutral-process.json").readText())
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel
        main { vm=ViewModelProvider(activity)[EditorViewModel::class.java] }
        await("engine") { vm.engine!=null && !vm.busy }
        val previousAuto=vm.smartAutoEnabled
        try {
            val uri=Uri.parse(record.getString("uri"))
            main { vm.smartAutoEnabled=false;vm.openPhotos(listOf(uri)) }
            idle(vm)
            check(EditSnapshot.capture(vm.params).json==record.getString("current") && vm.canRedo && vm.canUndo)
            main { vm.redo() }; idle(vm)
            check(EditSnapshot.capture(vm.params).json==record.getString("redo") && vm.params.cameraWbTemperatureAsShot && !vm.params.cameraWbTintAsShot)
            main { vm.undo() }; idle(vm)
            check(EditSnapshot.capture(vm.params).json==record.getString("current") && vm.canRedo)
            return "New process restores picked RAW WB and mixed-axis redo branch; real decode on undo/redo preserves both saved snapshots"
        } finally { main { vm.smartAutoEnabled=previousAuto;activity.finish() } }
    }
}
