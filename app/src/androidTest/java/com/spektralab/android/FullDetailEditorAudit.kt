package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Half
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.geometry.FinishedCrop
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class FullDetailEditorAudit(private val test:Instrumentation,private val output:File) {
    private val context=test.targetContext
    private fun main(action:()->Unit)=test.runOnMainSync(action)
    private fun await(label:String,ready:()->Boolean){val start=SystemClock.elapsedRealtime();while(!ready()){check(SystemClock.elapsedRealtime()-start<180000){"Detail timeout $label"};Thread.sleep(40)};test.waitForIdleSync()}
    private fun find(label:String,node:AccessibilityNodeInfo?=test.uiAutomation.rootInActiveWindow):AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.isVisibleToUser && (node.text?.toString()==label || node.contentDescription?.toString()==label))return node
        for(i in 0 until node.childCount)find(label,node.getChild(i))?.let{return it};return null
    }
    private fun click(label:String) {
        await(label){test.uiAutomation.clearCache();find(label)!=null};var node=find(label)
        while(node!=null && !node.isClickable)node=node.parent
        check(node!=null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK));test.waitForIdleSync();Thread.sleep(180)
    }
    private fun capture(name:String){test.waitForIdleSync();Thread.sleep(200);val bitmap=test.uiAutomation.takeScreenshot()!!
        try{File(output,"r09-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}}
    fun run(raw:Boolean,layout:Boolean):String {
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel;main{vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        try {
            await("engine"){vm.engine!=null && !vm.busy}
            val file=if(raw)File(output,"pc-parity-DSC_5099.NEF")else File(output,"r09-photo.jpg").also{target->
                val photo=test.context.assets.open("13mb.jpg").use{BitmapFactory.decodeStream(it)}!!
                val scaled=Bitmap.createScaledBitmap(photo,1601,(photo.height.toLong()*1601/photo.width).toInt(),true)
                try{target.outputStream().use{scaled.compress(Bitmap.CompressFormat.JPEG,95,it)}}finally{scaled.recycle();photo.recycle()}
            }
            val uri=Uri.fromFile(file)
            val edit=EditorParams().apply{grainActive=false;halationActive=false;glareActive=false
                if(!raw){quarterTurns=1;finishedCrop=FinishedCrop(.07f,.09f,.83f,.81f);brightness=5f;vignetteAmount=13f}}
            EditStore.save(context,uri,edit);main{vm.smartAutoEnabled=false;vm.openPhotos(listOf(uri))}
            await("source"){vm.errorMessage!=null || vm.canPickNeutral && !vm.busy};check(vm.errorMessage==null){vm.errorMessage.orEmpty()}
            val snapshot=EditSnapshot.capture(vm.params)
            val initial=media()
            val receiptNames=File(context.filesDir,"export-jobs").list().orEmpty().toSet()
            if(layout){await("home"){test.uiAutomation.clearCache();find("继续显影")!=null};click("继续显影");click("导出")}
            val start=SystemClock.elapsedRealtime()
            main{vm.exportFormat="TIFF";vm.exportColorSpace="Adobe RGB (1998)";vm.exportRes=2560;vm.exportCustomSize=ExportSize(ExportSize.Kind.LONG_EDGE,317.0)
                vm.openFullDetail()}
            await("full session"){!vm.isExporting && !vm.detail.loading};check(vm.detail.error==null){vm.detail.error.orEmpty()}
            val session=checkNotNull(vm.detail.session)
            check(media()==initial && EditSnapshot.capture(vm.params)==snapshot)
            check((File(context.filesDir,"export-jobs").list().orEmpty().toSet()-receiptNames).isEmpty()) {"A view-only job created an export receipt"}
            if(!layout)main{vm.detail.resize(701,509,"sRGB")}
            await("tiles"){vm.detail.frame!=null};val frame=vm.detail.frame!!
            check(session.colorSpace=="Adobe RGB (1998)")
            // Independent complete render is authored through the same frozen native frame,
            // then compared at every visible tile sample after the known output transform.
            main{vm.engine!!.park()}
            EngineClient.create(context).use{engine->
                val options=ExportOptions("TIFF",0,"Adobe RGB (1998)",95,false,false,true)
                ExportSourceDecoder.open(context,engine,uri,raw,edit,options){}
                engine.renderExportOnce().use{full->
                    val cut=FinishedRgba16Source.wrap(full,EditorGrade.from(edit),edit.finishedCrop){}
                    val converted=OutputConvertedSource.wrap(cut,if(cut.gradeApplied)EditorGrade.identity else EditorGrade.from(edit),"sRGB","Adobe RGB (1998)"){}
                    check(session.width==converted.width && session.height==converted.height)
                    val transform=android.graphics.ColorSpace.connect(com.spektralab.android.core.color.OutputColorSpaces.named("Adobe RGB (1998)"),android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                    val row=ShortArray(converted.width*4);val rgb=FloatArray(3)
                    for((tile,bitmap)in frame.tiles){
                        val bytes=ByteBuffer.allocate(bitmap.allocationByteCount).order(ByteOrder.nativeOrder());bitmap.copyPixelsToBuffer(bytes);bytes.rewind()
                        val actual=ShortArray(tile.width*tile.height*4);bytes.asShortBuffer().get(actual)
                        for(y in 0 until tile.height){converted.readRows(tile.y+y,1,row)
                            for(x in 0 until tile.width){for(c in 0..2)rgb[c]=(row[(tile.x+x)*4+c].toInt() and 65535)/65535f
                                transform.transform(rgb);for(c in 0..2)check(actual[(y*tile.width+x)*4+c]==Half.toHalf(rgb[c]))}
                        }
                    }
                }
            }
            if(layout) {
                await("actual viewport"){vm.detail.frame!=null};capture("100")
                click("200%");await("scale2"){vm.detail.frame?.view?.scale==2f};capture("200")
                val rect=android.graphics.Rect();find("原尺寸细节画布")!!.getBoundsInScreen(rect)
                val down=SystemClock.uptimeMillis()
                for((i,action)in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP).withIndex()) {
                    val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,rect.centerX()+i*35f,rect.centerY()+i*17f,0)
                    try{check(test.uiAutomation.injectInputEvent(event,true))}finally{event.recycle()}
                };Thread.sleep(400);capture("pan")
                click("400%");await("scale4"){vm.detail.frame?.view?.scale==4f};capture("400")
                click("关闭原尺寸细节");check(!vm.detail.opened);capture("returned")
            }else {
                repeat(30){i->main{vm.detail.gesture(i*3f,-i*2f,1.02f,311f,203f)}}
                main{vm.detail.scale(4f)};await("latest viewport"){vm.detail.frame?.view==vm.detail.view && vm.detail.frame?.view?.scale==4f}
                main{vm.closeFullDetail()};check(vm.detail.frame==null && !vm.detail.opened)
            }
            await("session closed"){runCatching{session.visible(DetailViewport(),10,10,"sRGB") {}}.isFailure}
            check(EditSnapshot.capture(EditStore.load(context,uri)!!)==snapshot)
            if(!raw && !layout){
                main{vm.exportFormat="PNG";vm.exportColorSpace="ProPhoto RGB";vm.exportCustomSize=ExportSize(ExportSize.Kind.LONG_EDGE,317.0);vm.softProof()}
                await("proof"){!vm.isExporting};check(vm.errorMessage==null){vm.errorMessage.orEmpty()}
                check(maxOf(vm.rendered!!.width,vm.rendered!!.height)==317 && vm.rendered!!.config==Bitmap.Config.RGBA_F16)
                check(media()==initial)
                main{vm.openFullDetail();vm.closeFullDetail()};await("cancel"){!vm.isExporting};check(!vm.detail.opened && vm.detail.session==null && media()==initial)
                main{vm.openFullDetail()};await("reopen"){!vm.isExporting && !vm.detail.loading};check(vm.detail.session!=null)
                main{vm.params.brightness+=1f;vm.scheduleGrade()};check(!vm.detail.opened && vm.detail.frame==null)
                main{vm.openFullDetail()};await("background session"){!vm.isExporting && !vm.detail.loading}
                main{activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)};check(!vm.detail.opened)
            }
            File(output,if(raw)"r09-raw-time.txt" else "r09-editor-time.txt").writeText((SystemClock.elapsedRealtime()-start).toString())
            return "Original NEF=$raw; native full finished pixels match every visible F16 tile; crop/grade/file/display transforms, full dimensions ignore317px output limit, immutable edits/no publication, rapid requests/close/cancel/background invalidation; layout=$layout"
        }finally{main{vm.closeFullDetail();activity.finish()}}
    }
    private fun media()=context.contentResolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf("_id"),null,null,null)!!.use{c->buildSet{while(c.moveToNext())add(c.getLong(0))}}
}
