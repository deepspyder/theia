package com.spektralab.android

import android.app.Instrumentation
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.color.*
import com.spektralab.android.core.geometry.FinishedCrop
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.panels.CurveEditor
import com.spektralab.android.feature.editor.ui.panels.ShootPanel
import com.spektralab.android.ui.theme.TheiaTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal class SceneCurveEditorAudit(private val test: Instrumentation, private val output: File) {
    private val context = test.targetContext
    private val timings = JSONArray()
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun await(label: String, condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 180000) { "Scene curve timeout: $label" }
            Thread.sleep(40)
        }
        test.waitForIdleSync()
    }
    private fun idle(vm: EditorViewModel) {
        await("editor ${vm.errorMessage}") { vm.errorMessage != null || vm.hasPhoto && !vm.busy && vm.canPickNeutral && !vm.isExporting && !vm.isBatchExporting }
        check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
        Thread.sleep(300); test.waitForIdleSync()
    }
    private fun find(label: String, node: AccessibilityNodeInfo? = test.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(label, node.getChild(i))?.let { return it }
        return null
    }
    private fun click(label: String) {
        await(label) { test.uiAutomation.clearCache(); find(label)?.isVisibleToUser == true }
        var node = find(label)
        while (node != null && !node.isClickable) node = node.parent
        check(node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
        test.waitForIdleSync(); Thread.sleep(200)
    }
    private fun capture(label: String) {
        test.waitForIdleSync(); Thread.sleep(200)
        val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
        try { File(output, "scene-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        test.uiAutomation.clearCache()
    }
    private fun export(vm: EditorViewModel, format: String, size: ExportSize? = null): Uri {
        val start = SystemClock.elapsedRealtime()
        main { vm.exportFormat = format; vm.exportRes = 0; vm.exportCustomSize = size
            vm.exportKeepCapture = false; vm.exportKeepLocation = false; vm.export(); check(vm.isExporting) }
        await("export") { !vm.isExporting }
        check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
        val uri = Uri.parse(vm.log.substringAfterLast("相册: ").lineSequence().first().trim())
        check(uri.scheme == "content") { vm.log }
        timings.put(JSONObject().put("format", format).put("size", size?.toJson()).put("ms", SystemClock.elapsedRealtime() - start))
        return uri
    }
    private fun sha(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun verify(uri: Uri, source: Rgba16Source, format: String) {
        if (format == "TIFF") {
            context.contentResolver.openInputStream(uri)!!.use { stream ->
                val head = ByteArray(8); check(stream.read(head) == 8)
                val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                check(b.getShort(0).toInt() == 0x4949 && b.getShort(2).toInt() == 42 && b.getInt(4) == 8)
                val countBytes = ByteArray(2); check(stream.read(countBytes) == 2)
                val count = ByteBuffer.wrap(countBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val entries = ByteArray(count * 12 + 4); var read = 0
                while (read < entries.size) { val n = stream.read(entries, read, entries.size - read); check(n > 0); read += n }
                val fields = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN)
                val tags = (0 until count).associate { at -> fields.getShort(at * 12).toInt() to fields.getInt(at * 12 + 8) }
                check(tags[256] == source.width && tags[257] == source.height)
                val skip = tags.getValue(273) - (10 + entries.size)
                var left = skip.toLong(); while (left > 0) { val n = stream.skip(left); check(n > 0); left -= n }
                val expected = ShortArray(source.width * 4); val bytes = ByteArray(source.width * 6)
                for (y in 0 until source.height) {
                    var n = 0; while (n < bytes.size) { val next = stream.read(bytes, n, bytes.size - n); check(next > 0); n += next }
                    source.readRows(y, 1, expected)
                    val row = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (x in 0 until source.width) for (c in 0..2) check(row.short == expected[x * 4 + c])
                }
                check(stream.read() == -1)
            }
        } else {
            val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }!!
            val expected = Rgba16Bitmap.convert(source, EditorGrade.identity) {}
            try {
                check(bitmap.width == source.width && bitmap.height == source.height)
                val a = IntArray(bitmap.width); val b = IntArray(bitmap.width); var error = 0L; var channels = 0
                for (y in 0 until bitmap.height) {
                    bitmap.getPixels(a, 0, a.size, 0, y, a.size, 1); expected.getPixels(b, 0, b.size, 0, y, b.size, 1)
                    for (x in a.indices) for (shift in listOf(0,8,16)) {
                        val delta = kotlin.math.abs(((a[x] ushr shift) and 255) - ((b[x] ushr shift) and 255))
                        if (format == "PNG") check(delta <= 1)
                        error += delta; channels++
                    }
                }
                if (format == "JPEG") check(error.toDouble() / channels < 6) { "JPEG mean error ${error.toDouble()/channels}" }
            } finally { bitmap.recycle(); expected.recycle() }
        }
    }

    fun run(mode: String): String {
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await("engine") { vm.engine != null && !vm.busy && vm.films.isNotEmpty() }
        val automatic = vm.smartAutoEnabled; main { vm.smartAutoEnabled = false }
        try {
            if (mode == "scene-curve-process") {
                val saved = JSONObject(File(output, "scene-process.json").readText())
                main { vm.openPhotos(listOf(Uri.parse(saved.getString("uri")))) }; idle(vm)
                check(EditSnapshot.capture(vm.params).json == saved.getString("current") && vm.canRedo)
                main { vm.redo() }; idle(vm); check(EditSnapshot.capture(vm.params).json == saved.getString("redo"))
                main { vm.undo() }; idle(vm); check(EditSnapshot.capture(vm.params).json == saved.getString("current"))
                return "New process restored Decode curve, independent post curve, full geometry and redo branch"
            }
            if (mode == "scene-curve-raw") return raw(vm)
            if (mode == "scene-curve-layout") return layout(activity, vm)
            var exports = 0
            for (kind in listOf("PNG", "WEBP", "JPEG")) {
                val file = File(output, "scene-${System.nanoTime()}.$kind")
                val image = Bitmap.createBitmap(601, 403, Bitmap.Config.ARGB_8888)
                image.setPixels(IntArray(601*403) { i -> 0xff000000.toInt() or ((30 + i%601*190/601) shl 16) or
                    ((40 + i/601*170/403) shl 8) or 111 }, 0, 601, 0, 0, 601, 403)
                try { file.outputStream().use { image.compress(when(kind) { "PNG" -> Bitmap.CompressFormat.PNG
                    "JPEG" -> Bitmap.CompressFormat.JPEG; else -> Bitmap.CompressFormat.WEBP_LOSSLESS }, 100, it) } }
                finally { image.recycle() }
                if (kind == "JPEG") ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION,"6"); saveAttributes() }
                val uri = Uri.fromFile(file)
                val p = EditorParams().apply { grainActive=false; glareActive=false; halationActive=false; autoExposure=false
                    perspectiveVertical=7f; quarterTurns=1; cropX=.08f; cropY=.1f; cropW=.82f; cropH=.79f
                    finishedCrop=FinishedCrop(.1f,.1f,.8f,.8f); vignetteAmount=31f }
                EditStore.save(context,uri,p); main {vm.openPhotos(listOf(uri))}; idle(vm)
                val baseline=EditSnapshot.capture(vm.params)
                main {vm.beginSceneCurveEdit();vm.changeSceneCurves(SceneCurveAudit.shaped().curves)}; idle(vm)
                check(EditSnapshot.capture(EditStore.load(context,uri)!!)==baseline) {"Draft scene curve escaped to storage"}
                main {vm.finishSceneCurveEdit(cancel=true)}; idle(vm); check(EditSnapshot.capture(vm.params)==baseline)
                main {vm.beginSceneCurveEdit();vm.changeSceneCurves(SceneCurveAudit.shaped().curves);vm.finishSceneCurveEdit()}; idle(vm)
                val scene=EditSnapshot.capture(vm.params)
                main {vm.beginCurveEdit();vm.changeCurves(ToneCurves.IDENTITY.with(CurveChannel.RED,ToneCurve.IDENTITY.add(.5f,.53f)));vm.finishCurveEdit()};idle(vm)
                val both=EditSnapshot.capture(vm.params)
                main {vm.undo()};idle(vm);check(EditSnapshot.capture(vm.params)==scene)
                main {vm.redo()};idle(vm);check(EditSnapshot.capture(vm.params)==both)
                main {vm.openPhoto(uri)};idle(vm);check(EditSnapshot.capture(vm.params)==both)
                val frozen=vm.params.copy()
                EngineClient.create(context).use {engine ->
                    val source=ExportSourceDecoder.bitmap(context,uri,0)
                    try {engine.openBitmap(source,frozen.openJson(source.width,source.height))}finally{source.recycle()}
                    engine.renderExportOnce().use {base ->
                        val finished=FinishedRgba16Source.wrap(base,EditorGrade.from(frozen),frozen.finishedCrop){}
                        for(size in listOf<ExportSize?>(null,ExportSize(ExportSize.Kind.LONG_EDGE,137.0))) {
                            val expected=SizedRgba16Source.wrap(finished,size){}
                            for(format in listOf("PNG","TIFF","JPEG")) {
                                val out=export(vm,format,size)
                                try {verify(out,expected,format)}finally{context.contentResolver.delete(out,null,null)}
                                exports++
                            }
                        }
                    }
                }
                main {vm.params.filmOff=true;vm.scheduleRender()};idle(vm);Thread.sleep(400)
                check(vm.params.sceneCurves==frozen.sceneCurves)
                main {vm.params.filmOff=false;vm.scheduleRender()};idle(vm)
                check(EditSnapshot.capture(vm.params)==both)
                main {vm.undo()};idle(vm);check(vm.params.filmOff && vm.canRedo)
                File(output,"scene-process.json").writeText(JSONObject().put("uri",uri.toString())
                    .put("current",EditSnapshot.capture(vm.params).json).put("redo",both.json).toString())
            }
            File(output,"scene-editor-timing.json").writeText(timings.toString(2))
            return "$exports actual PNG/TIFF/JPEG original+custom exports across PNG/WebP/EXIF6 JPEG; full pixels/U16 rows, live draft/cancel/commit, Decode+post isolation, history/reopen, film bypass restoration"
        } finally { main { vm.smartAutoEnabled=automatic;activity.finish() } }
    }

    private fun raw(vm: EditorViewModel): String {
        val uri=Uri.fromFile(File(output,"pc-parity-DSC_5099.NEF"));check(File(uri.path!!).isFile)
        val old=EditStore.load(context,uri)
        try {
            val p=EditorParams().apply {grainActive=false;halationActive=false;glareActive=false;sceneCurves=SceneCurveAudit.shaped()
                cameraWbCustom=true;cameraWbKelvin=6100f;cameraWbTint=7f}
            EditStore.save(context,uri,p);main{vm.openPhotos(listOf(uri))};idle(vm)
            val snapshots=mutableListOf<String>()
            for(format in listOf("PNG","TIFF","JPEG")) {
                val out=export(vm,format)
                try {
                    if(format=="TIFF") {
                        val header=ByteArray(4096)
                        context.contentResolver.openInputStream(out)!!.use { stream ->
                            var count=0;while(count<header.size){val n=stream.read(header,count,header.size-count);check(n>0);count+=n}
                        }
                        val buffer=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                        val ifd=buffer.getInt(4)
                        val tags=(0 until buffer.getShort(ifd).toInt()).associate {index ->
                            val at=ifd+2+index*12;buffer.getShort(at).toInt() to buffer.getInt(at+8)
                        }
                        check(tags[256]==6064 && tags[257]==4040 && tags[279]==6064*4040*6)
                    } else {
                        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                        context.contentResolver.openInputStream(out).use {BitmapFactory.decodeStream(it,null,bounds)}
                        check(bounds.outWidth==6064 && bounds.outHeight==4040)
                    }
                    snapshots+=sha(out)
                }finally{context.contentResolver.delete(out,null,null)}
            }
            val again=export(vm,"PNG")
            try {check(sha(again)==snapshots.first())}finally{context.contentResolver.delete(again,null,null)}
            main {vm.params.cameraWbTint=13f;vm.scheduleCameraWb()};idle(vm);check(vm.params.sceneCurves==p.sceneCurves)
            val small=export(vm,"PNG",ExportSize(ExportSize.Kind.SHORT_EDGE,333.0))
            try {check(sha(small).isNotEmpty())}finally{context.contentResolver.delete(small,null,null)}
            File(output,"scene-raw-timing.json").writeText(timings.toString(2))
            return "6064x4040 NEF with Decode curve in actual three-container original exports, repeated full PNG SHA, custom short-edge and camera WB re-decode; $timings"
        }finally{old?.let{EditStore.save(context,uri,it)}}
    }

    private fun layout(activity: MainActivity, vm: EditorViewModel): String {
        val file=File(output,"scene-ui-${System.nanoTime()}.jpg")
        test.context.assets.open("13mb.jpg").use {input->file.outputStream().use {input.copyTo(it)}}
        val uri=Uri.fromFile(file)
        EditStore.save(context,uri,EditorParams().apply {grainActive=false;halationActive=false;glareActive=false})
        main{vm.openPhotos(listOf(uri))};idle(vm)
        main {activity.setContent {TheiaTheme {Surface {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {ShootPanel(vm)}
        }}}};test.waitForIdleSync();capture("entry")
        click(context.getString(R.string.scene_curve_edit))
        val plot=context.getString(R.string.scene_curve_plot,context.getString(R.string.curve_rgb))
        await("plot"){test.uiAutomation.clearCache();find(plot)!=null}
        capture("rgb")
        val footer = Rect();checkNotNull(find(context.getString(R.string.curve_more))).getBoundsInScreen(footer)
        val window = Rect();test.uiAutomation.rootInActiveWindow.getBoundsInScreen(window)
        check(footer.bottom <= window.bottom - 12 * context.resources.displayMetrics.density) { "Curve footer overlaps navigation: $footer / $window" }
        val rect=Rect();find(plot)!!.getBoundsInScreen(rect)
        val density=context.resources.displayMetrics.density;val inset=18*density
        fun point(x:Float,y:Float)= (rect.left+inset+x*(rect.width()-2*inset)) to (rect.top+inset+(1-y)*(rect.height()-2*inset))
        var down=SystemClock.uptimeMillis()
        fun touch(action:Int,position:Pair<Float,Float>){if(action==MotionEvent.ACTION_DOWN)down=SystemClock.uptimeMillis()
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,position.first,position.second,0)
            try{check(test.uiAutomation.injectInputEvent(event,true))}finally{event.recycle()}}
        val baseline=EditSnapshot.capture(vm.params)
        touch(MotionEvent.ACTION_DOWN,point(.5f,.5f));touch(MotionEvent.ACTION_MOVE,point(.56f,.63f));touch(MotionEvent.ACTION_CANCEL,point(.56f,.63f))
        idle(vm);check(EditSnapshot.capture(vm.params)==baseline)
        touch(MotionEvent.ACTION_DOWN,point(.5f,.5f));touch(MotionEvent.ACTION_MOVE,point(.55f,.62f));touch(MotionEvent.ACTION_UP,point(.55f,.62f))
        idle(vm);check(!vm.params.sceneCurves.isIdentity && vm.params.toneCurves.isDefault);capture("changed")
        val changed=EditSnapshot.capture(vm.params)
        click(context.getString(R.string.curve_undo));idle(vm);check(EditSnapshot.capture(vm.params)==baseline)
        click(context.getString(R.string.curve_redo));idle(vm);check(EditSnapshot.capture(vm.params)==changed)
        for(channel in listOf(R.string.curve_luma,R.string.curve_red,R.string.curve_green,R.string.curve_blue))click(context.getString(channel))
        capture("blue")
        click(context.getString(R.string.curve_show_precision));capture("precision")
        click(context.getString(R.string.curve_more));capture("reset-menu")
        click(context.getString(R.string.curve_reset_all));idle(vm);check(vm.params.sceneCurves.isDefault)
        click(context.getString(R.string.curve_done));test.waitForIdleSync()
        main {vm.params.filmOff=true;vm.scheduleRender()};idle(vm);capture("off")
        test.uiAutomation.clearCache();var button=find(context.getString(R.string.scene_curve_edit))
        while(button!=null && !button.isClickable)button=button.parent
        check(button!=null && !button.isEnabled)
        main{vm.params.filmOff=false;vm.scheduleRender()};idle(vm)
        val future=EditorParams.fromJson(vm.params.toJson().put("scene_curves",JSONObject().put("version",99).put("keep","safe")))
        EditStore.save(context,uri,future);main{vm.openPhoto(uri)};idle(vm)
        click(context.getString(R.string.scene_curve_edit));capture("unsupported")
        check(vm.params.unreadableSceneCurves!=null)
        main {vm.export();check(!vm.isExporting);check(vm.errorMessage==context.getString(R.string.scene_curve_unsupported));vm.dismissError()}
        check(EditStore.load(context,uri)!!.unreadableSceneCurves!=null)
        click(context.getString(R.string.curve_reset_all));idle(vm);check(vm.params.unreadableSceneCurves==null)
        click(context.getString(R.string.curve_done))
        return "Real photo: actual Decode entry/five channels/EV graph, cancelled and committed drag, undo/redo, precision/reset, dormant film-off control and explicit future-curve reset; current viewport screenshots"
    }
}
