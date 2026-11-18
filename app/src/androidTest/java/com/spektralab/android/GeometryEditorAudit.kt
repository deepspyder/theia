package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.content.ContentUris
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.geometry.FinishedCrop
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.crop.CropWorkspace
import com.spektralab.android.feature.editor.ui.panels.*
import com.spektralab.android.ui.theme.TheiaTheme
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal class GeometryEditorAudit(private val test: Instrumentation,private val output: File) {
    private val context=test.targetContext
    private val timings=JSONArray()
    private fun main(action:()->Unit)=test.runOnMainSync(action)
    private fun await(label:String,condition:()->Boolean) {
        val start=SystemClock.elapsedRealtime()
        while(!condition()) {check(SystemClock.elapsedRealtime()-start<150000) {"Geometry timeout $label"};Thread.sleep(40)}
        test.waitForIdleSync()
    }
    private fun idle(vm:EditorViewModel) {
        await("editor ${vm.errorMessage}"){vm.errorMessage!=null || vm.hasPhoto && !vm.busy && vm.canPickNeutral && !vm.isExporting && !vm.isBatchExporting}
        check(vm.errorMessage==null){vm.errorMessage.orEmpty()};Thread.sleep(350);test.waitForIdleSync()
    }
    private fun find(label:String,node:AccessibilityNodeInfo?=test.uiAutomation.rootInActiveWindow):AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.text?.toString()==label || node.contentDescription?.toString()==label)return node
        for(i in 0 until node.childCount)find(label,node.getChild(i))?.let{return it}
        return null
    }
    private fun click(label:String) {
        test.uiAutomation.clearCache()
        await(label){find(label)?.isVisibleToUser==true}
        var node=find(label)
        while(node!=null && !node.isClickable)node=node.parent
        check(node!=null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {"Cannot click $label"}
        test.waitForIdleSync();Thread.sleep(200)
    }
    private fun capture(label:String) {
        Thread.sleep(250);test.waitForIdleSync()
        val bitmap=checkNotNull(test.uiAutomation.takeScreenshot())
        try {File(output,"geometry-$label.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
        test.uiAutomation.clearCache()
    }
    private fun digest(uri:Uri):String {
        val hash=MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)!!.use {stream->val bytes=ByteArray(65536);while(true){val n=stream.read(bytes);if(n<0)break;hash.update(bytes,0,n)}}
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
    private fun export(vm:EditorViewModel,format:String,size:ExportSize?=null):Uri {
        val start=SystemClock.elapsedRealtime()
        main {vm.exportFormat=format;vm.exportRes=0;vm.exportCustomSize=size;vm.exportKeepCapture=false;vm.exportKeepLocation=false;vm.export();check(vm.isExporting)}
        await("export $format"){!vm.isExporting};check(vm.errorMessage==null){vm.errorMessage.orEmpty()}
        val uri=Uri.parse(vm.log.substringAfterLast("相册: ").lineSequence().first().trim());check(uri.scheme=="content"){vm.log}
        timings.put(JSONObject().put("format",format).put("size",size?.toJson()).put("ms",SystemClock.elapsedRealtime()-start))
        return uri
    }
    private fun comparePng(uri:Uri,reference:Bitmap) {
        val actual=context.contentResolver.openInputStream(uri).use {BitmapFactory.decodeStream(it)}!!
        try {
            check(actual.width==reference.width && actual.height==reference.height){"${actual.width}x${actual.height} vs ${reference.width}x${reference.height}"}
            val a=IntArray(actual.width);val b=IntArray(a.size)
            var max=0
            for(y in 0 until actual.height){actual.getPixels(a,0,a.size,0,y,a.size,1);reference.getPixels(b,0,b.size,0,y,b.size,1)
                for(x in a.indices)for(shift in listOf(0,8,16))max=maxOf(max,kotlin.math.abs(((a[x] ushr shift)and 255)-((b[x] ushr shift)and 255)))}
            check(max<=1){"PNG pixel drift $max"}
        } finally {actual.recycle()}
    }
    fun run(mode:String):String {
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel
        main {vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        await("engine"){vm.engine!=null && !vm.busy && vm.films.isNotEmpty()}
        val auto=vm.smartAutoEnabled;main {vm.smartAutoEnabled=false}
        try {
            if(mode=="geometry-navigation") {
                main {vm.openTestImage()};idle(vm)
                check(vm.sourceBitmap==null)
                val canvas=com.spektralab.android.feature.lighttable.FilmCanvasState()
                main {activity.setContent {TheiaTheme {
                    com.spektralab.android.feature.editor.ui.EditorScreen(vm, {}, {}, {}, canvas, 0)
                }}}
                test.waitForIdleSync();Thread.sleep(250)
                val original=vm.params.finishedCrop
                main {vm.beginFinishedCrop()};await("test-image cut"){vm.isCropping}
                test.uiAutomation.clearCache();await("actual editor crop"){find("成片裁切")!=null}
                main {vm.updateCropSelection(.1f,.1f,.8f,.8f);activity.onBackPressedDispatcher.onBackPressed()}
                test.waitForIdleSync();check(!vm.isCropping && vm.params.finishedCrop==original)
                main {vm.beginFinishedCrop()};await("cut again"){vm.isCropping}
                test.uiAutomation.clearCache()
                main {vm.updateCropSelection(.1f,.1f,.8f,.8f)}
                click("完成裁剪");idle(vm);check(!vm.isCropping && vm.params.finishedCrop.width==.8f)
                return "Real EditorScreen opens finished crop without sourceBitmap; system Back cancels and Done returns to editor"
            }
            if(mode=="geometry-process") {
                val record=JSONObject(File(output,"geometry-process.json").readText())
                main {vm.openPhotos(listOf(Uri.parse(record.getString("uri"))))};idle(vm)
                check(EditSnapshot.capture(vm.params).json==record.getString("current") && vm.canRedo)
                main {vm.redo()};idle(vm);check(EditSnapshot.capture(vm.params).json==record.getString("redo"))
                main {vm.undo()};idle(vm);check(EditSnapshot.capture(vm.params).json==record.getString("current"))
                return "Separate process restored source perspective, film toggle and finished cut plus redo branch"
            }
            fun open(uri:Uri){main {vm.openPhotos(listOf(uri))};idle(vm)}
            if(mode=="geometry-raw-export") {
                val uri=Uri.fromFile(File(output,"pc-parity-DSC_5099.NEF"));check(File(uri.path!!).isFile)
                val previous=EditStore.load(context,uri)
                try {
                    val p=EditorParams().apply {filmOff=true;perspectiveVertical=13f;perspectiveHorizontal=-9f;finishedCrop=FinishedCrop(.1f,.15f,.8f,.7f);cameraWbCustom=true;cameraWbKelvin=6100f;cameraWbTint=7f}
                    EditStore.save(context,uri,p);open(uri)
                    val first=export(vm,"PNG")
                    val sha=try {
                        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                        context.contentResolver.openInputStream(first).use {BitmapFactory.decodeStream(it,null,bounds)}
                        val cut=p.finishedCrop.pixels(6064,4040);check(bounds.outWidth==cut.width && bounds.outHeight==cut.height)
                        digest(first)
                    }finally {context.contentResolver.delete(first,null,null)}
                    val second=export(vm,"PNG")
                    try {check(digest(second)==sha)}finally {context.contentResolver.delete(second,null,null)}
                    main {vm.params.cameraWbTint=19f;vm.scheduleCameraWb()};idle(vm)
                    check(vm.params.filmOff && vm.params.finishedCrop==p.finishedCrop)
                    main {vm.previewTier()};await("RAW HD"){!vm.isExporting};check(vm.errorMessage==null){vm.errorMessage.orEmpty()}
                    val hdCut=p.finishedCrop.pixels(vm.lastResult!!.width,vm.lastResult!!.height)
                    check(vm.rendered!!.width==hdCut.width && vm.rendered!!.height==hdCut.height)
                    val small=export(vm,"TIFF",ExportSize(ExportSize.Kind.LONG_EDGE,777.0))
                    try {check(context.contentResolver.openInputStream(small)!!.use {it.readBytes().size}>1000)}finally{context.contentResolver.delete(small,null,null)}
                    File(output,"geometry-raw-export-timing.json").writeText(timings.toString(2))
                    return "6064x4040 NEF perspective/digital/crop, exact full PNG cache hash and custom TIFF; RAW white-balance re-decode retains geometry; $timings"
                }finally {previous?.let {EditStore.save(context,uri,it)}}
            }
            val kinds=if(mode=="geometry-layout")listOf("PNG")else listOf("PNG","WEBP","JPEG1","JPEG3","JPEG6","JPEG8")
            var exports=0
            var processUri:Uri?=null
            for(kind in kinds) {
                val image = if (mode == "geometry-layout") {
                    val photo = test.context.assets.open("13mb.jpg").use { BitmapFactory.decodeStream(it) }!!
                    try { Bitmap.createScaledBitmap(photo, 901, (photo.height.toLong()*901/photo.width).toInt(), true) }
                    finally { photo.recycle() }
                } else Bitmap.createBitmap(601,403,Bitmap.Config.ARGB_8888)
                if (mode != "geometry-layout") image.setPixels(IntArray(601*403){i->0xff000000.toInt() or ((i%601*180/601+30) shl 16) or ((i/601*160/403+40)shl 8) or 107},0,601,0,0,601,403)
                val file=File(output,"geometry-${System.nanoTime()}-${kind}.${if(kind.startsWith("JPEG"))"jpg" else kind.lowercase()}")
                try{file.outputStream().use {image.compress(when {kind=="PNG"->Bitmap.CompressFormat.PNG;kind=="WEBP"->Bitmap.CompressFormat.WEBP_LOSSLESS;else->Bitmap.CompressFormat.JPEG},100,it)}}finally{image.recycle()}
                if(kind.startsWith("JPEG"))ExifInterface(file).apply {setAttribute(ExifInterface.TAG_ORIENTATION,kind.removePrefix("JPEG"));saveAttributes()}
                val uri=Uri.fromFile(file);processUri=uri
                val edit=EditorParams().apply {filmOff=true;grainActive=false;halationActive=false;glareActive=false;perspectiveVertical=11f;perspectiveHorizontal=-7f
                    quarterTurns=1;flipH=true;rotationDeg=3.5f;cropX=.1f;cropY=.13f;cropW=.8f;cropH=.72f;vignetteAmount=77f;vignetteSize=35f;contrast=7f}
                EditStore.save(context,uri,edit);open(uri)
                val original=EditSnapshot.capture(vm.params)
                main {vm.beginFinishedCrop()};await("finished workspace"){vm.isCropping};check(vm.croppingFinished)
                main {vm.updateCropSelection(.1f,.17f,.71f,.67f);vm.cancelCrop()};check(EditSnapshot.capture(vm.params)==original)
                main {vm.beginFinishedCrop()};await("finished workspace"){vm.isCropping}
                main {vm.updateCropSelection(.1f,.17f,.71f,.67f);vm.finishCrop()};idle(vm)
                val edited=EditSnapshot.capture(vm.params)
                val cut=vm.params.finishedCrop.pixels(vm.lastResult!!.width,vm.lastResult!!.height)
                check(vm.rendered!!.width==cut.width && vm.rendered!!.height==cut.height)
                check(vm.histogram!!.r.sum()==cut.width*cut.height)
                main {vm.undo()};idle(vm);check(EditSnapshot.capture(vm.params)==original)
                main {vm.redo()};idle(vm);check(EditSnapshot.capture(vm.params)==edited)
                open(uri);check(EditSnapshot.capture(vm.params)==edited)
                if(mode=="geometry-layout") {
                    fun mount(panel:String) { main {activity.setContent {TheiaTheme {Surface { key(panel) {
                        if(vm.isCropping)CropWorkspace(vm,Modifier.fillMaxSize().safeDrawingPadding()) else Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
                            when(panel){"shoot"->ShootPanel(vm);"print"->PrintPanel(vm);else->GradePanel(vm)}
                        }
                    }}}}}
                        test.waitForIdleSync();Thread.sleep(300);test.uiAutomation.clearCache()
                    }
                    fun scrollEnd() {
                        fun scrollable(node:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                            if(node==null)return null
                            if(node.isScrollable)return node
                            for(i in 0 until node.childCount)scrollable(node.getChild(i))?.let{return it}
                            return null
                        }
                        repeat(10) { test.uiAutomation.clearCache();scrollable(test.uiAutomation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);test.waitForIdleSync();Thread.sleep(150) }
                    }
                    mount("grade");scrollEnd();click("几何与裁剪");scrollEnd();capture("geometry-controls")
                    test.uiAutomation.clearCache()
                    check(find("裁切成片")!=null)
                    mount("shoot");capture("shoot-off");click("启用胶片模拟");idle(vm);check(!vm.params.filmOff)
                    capture("shoot-on");click("启用胶片模拟");idle(vm);check(vm.params.filmOff)
                    mount("print");capture("print-off");check(find("放大机")==null)
                    main {vm.beginFinishedCrop()};await("workspace"){vm.isCropping};capture("finished-crop")
                    val before=vm.params.finishedCrop
                    test.uiAutomation.clearCache()
                    val bounds=Rect();checkNotNull(find("照片预览")) { "Crop preview missing from current accessibility tree" }.getBoundsInScreen(bounds)
                    fun touch(action:Int,x:Float,y:Float,down:Long){val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);try{test.uiAutomation.injectInputEvent(event,true)}finally{event.recycle()}}
                    val down=SystemClock.uptimeMillis();val x=bounds.exactCenterX();val y=bounds.exactCenterY()
                    touch(MotionEvent.ACTION_DOWN,x,y,down);touch(MotionEvent.ACTION_MOVE,x+20,y+12,down);touch(MotionEvent.ACTION_UP,x+20,y+12,down)
                    test.waitForIdleSync();check(vm.params.finishedCrop!=before)
                    click("取消");check(vm.params.finishedCrop==before && !vm.isCropping)
                    main {vm.beginCrop()};test.waitForIdleSync();capture("source-perspective-crop")
                    check(vm.cropWorkspaceBitmap!==vm.sourceBitmap && !vm.croppingFinished)
                    click("取消");check(!vm.isCropping)
                    main {vm.beginFinishedCrop()};await("workspace"){vm.isCropping};click("重置");click("完成裁剪");idle(vm);check(vm.params.finishedCrop.isFull)
                    capture("finished-reset")
                    return "Actual film toggle, hidden film controls, finished/source perspective crop drag/cancel/reset/apply, current viewport captures"
                }
                // Full output is compared against independently decoded source plus shared engine/grade in full coordinates.
                val p=vm.params.copy()
                EngineClient.create(context).use { engine ->
                    val source=ExportSourceDecoder.bitmap(context,uri,0)
                    try {engine.openBitmap(source,p.openJson(source.width,source.height))}finally{source.recycle()}
                    engine.renderExportOnce().use { base ->
                        val reference=FinishedRgba16Source.wrap(base,EditorGrade.from(p),p.finishedCrop){}
                        val expected=Rgba16Bitmap.convert(reference,EditorGrade.identity){}
                        try{
                            val actual=export(vm,"PNG");try{comparePng(actual,expected)}finally{context.contentResolver.delete(actual,null,null)};exports++
                            for(kindSize in ExportSize.Kind.entries) {
                                val size=ExportSize(kindSize,if(kindSize==ExportSize.Kind.MEGAPIXELS).03 else 137.0)
                                val small=SizedRgba16Source.wrap(reference,size){}
                                val expectedSmall=Rgba16Bitmap.convert(small,EditorGrade.identity){}
                                try{val out=export(vm,"PNG",size);try{comparePng(out,expectedSmall)}finally{context.contentResolver.delete(out,null,null)}}finally{expectedSmall.recycle()};exports++
                            }
                            for(format in listOf("JPEG","TIFF")) {
                                val out=export(vm,format)
                                try {
                                    val bytes=context.contentResolver.openInputStream(out)!!.use {it.readBytes()};check(bytes.size>100)
                                    if(format=="JPEG"){val b=BitmapFactory.decodeByteArray(bytes,0,bytes.size)!!;try{check(b.width==expected.width && b.height==expected.height)}finally{b.recycle()}}
                                    else {
                                        val buffer=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                        check(buffer.getShort(0).toInt()==0x4949 && buffer.getShort(2).toInt()==42)
                                        val ifd=buffer.getInt(4);val fields=(0 until buffer.getShort(ifd).toInt()).associate { index ->
                                            val at=ifd+2+index*12;buffer.getShort(at).toInt() to buffer.getInt(at+8)
                                        }
                                        check(fields[256]==reference.width && fields[257]==reference.height)
                                        val row=ShortArray(reference.width*4)
                                        for(y in 0 until reference.height) {
                                            reference.readRows(y,1,row)
                                            for(x in 0 until reference.width)for(c in 0..2)
                                                check(buffer.getShort(fields.getValue(273)+(y*reference.width+x)*6+c*2)==row[x*4+c])
                                        }
                                    }
                                }finally{context.contentResolver.delete(out,null,null)};exports++
                            }
                        }finally{expected.recycle()}
                    }
                }
                main {vm.softProof()};await("soft proof"){!vm.isExporting};check(vm.errorMessage==null){vm.errorMessage.orEmpty()}
                main {vm.previewTier()};await("HD"){!vm.isExporting};check(vm.errorMessage==null)
            }
            val uri=checkNotNull(processUri)
            open(uri)
            val current=EditSnapshot.capture(vm.params)
            main {vm.params.filmOff=false;vm.scheduleRender()};idle(vm)
            val redo=EditSnapshot.capture(vm.params)
            main {vm.undo()};idle(vm);check(EditSnapshot.capture(vm.params)==current && vm.canRedo)
            File(output,"geometry-process.json").writeText(JSONObject().put("uri",uri.toString()).put("current",current.json).put("redo",redo.json).toString())
            File(output,"geometry-editor-timing.json").writeText(timings.toString(2))
            return "6 PNG/WebP/JPEG EXIF1/3/6/8 inputs; $exports real original/custom PNG/JPEG/TIFF exports, full pixel comparisons, preview/histogram/cancel/apply/history/reopen, proof/HD; $timings"
        }finally {main {vm.smartAutoEnabled=auto;activity.finish()}}
    }
}
