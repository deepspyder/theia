package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.color.OutputIcc
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.runBlocking

/** Actual failed journal -> fresh UI -> frozen native retry -> one atomic published file. */
internal class ExportTasksEditorAudit(private val test:Instrumentation,private val output:File) {
    private val context=test.targetContext
    private fun main(action:()->Unit)=test.runOnMainSync(action)
    private fun await(label:String,ready:()->Boolean){val start=SystemClock.elapsedRealtime();while(!ready()){check(SystemClock.elapsedRealtime()-start<150000){"R10 timeout $label"};Thread.sleep(40)};test.waitForIdleSync()}
    private fun media()=context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf("_id"),null,null,null)!!.use{c->buildSet{while(c.moveToNext())add(c.getLong(0))}}
    private fun find(label:String,node:AccessibilityNodeInfo?=test.uiAutomation.rootInActiveWindow):AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.isVisibleToUser && (node.text?.toString()==label || node.contentDescription?.toString()==label))return node
        for(i in 0 until node.childCount)find(label,node.getChild(i))?.let{return it};return null
    }
    private fun click(label:String){await(label){test.uiAutomation.clearCache();find(label)!=null};var node=find(label)
        while(node!=null && !node.isClickable)node=node.parent;check(node!=null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK));test.waitForIdleSync();Thread.sleep(180)}
    private fun capture(name:String){test.waitForIdleSync();Thread.sleep(250);val bitmap=test.uiAutomation.takeScreenshot()!!
        try{File(output,"r10-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}}
    private fun strip(bytes:ByteArray):ByteArray {
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);val root=b.getInt(4);val n=b.getShort(root).toInt() and 65535
        fun field(tag:Int)=(0 until n).map{root+2+it*12}.first{b.getShort(it).toInt() and 65535==tag}.let{if(b.getShort(it+2).toInt()==3)b.getShort(it+8).toInt() and 65535 else b.getInt(it+8)}
        val data=bytes.copyOfRange(field(273),field(273)+field(279))
        return if(field(259)==8)InflaterInputStream(data.inputStream()).use{it.readBytes()}else data
    }
    fun run(layout:Boolean=false):String=runBlocking {
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel;main{vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        val store=ExportJobStore(context)
        val source=File(output,"R10_original_${System.nanoTime()}.png")
        val records=mutableListOf<String>();val initial=media()
        try {
            await("engine"){vm.engine!=null && !vm.busy}
            main{vm.dismissError()}
            val bitmap=Bitmap.createBitmap(601,403,Bitmap.Config.ARGB_8888)
            try {bitmap.setPixels(IntArray(601*403){i->0xff000000.toInt() or ((i%601*210/601+20)shl 16) or ((i/601*190/403+30)shl 8) or ((i*17)and 255)},0,601,0,0,601,403)
                source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
            val originalBytes=source.readBytes();val uri=Uri.fromFile(source)
            val edit=EditSnapshot.capture(EditorParams().apply{grainActive=true;glareActive=true;halationActive=true;brightness=3f;vignetteAmount=19f})
            val options=ExportOptions("TIFF",0,"ProPhoto RGB",95,false,false,true,
                ExportSize(ExportSize.Kind.LONG_EDGE,317.0),ExportNaming(listOf(ExportNaming.Token.ORIGINAL,ExportNaming.Token.DATE)),true)
            suspend fun failed():ExportFrameRequest {
                var frozen:ExportFrameRequest?=null
                val failure=runCatching{ExportCoordinator.get(context).run(uri,source.name,edit,options,{}){task,_->
                    frozen=task.request;task.phase(ExportJobStore.State.ENCODING);error("Intentional audit encoder interruption")}}
                check(failure.isFailure)
                val request=checkNotNull(frozen).copy(submittedAt=946684799000L,namingDate="1999-12-31")
                store.write(request,ExportJobStore.State.FAILED,error="Intentional audit encoder interruption");records+=request.jobId
                return request
            }
            val request=failed()
            main{vm.exportTasks.open()};await("journal"){!vm.exportTasks.loading && vm.exportTasks.entries.any{it.id==request.jobId}}
            if(layout){await("task screen"){test.uiAutomation.clearCache();find("导出任务")!=null};capture("failed")}
            val reference=ByteArrayOutputStream()
            main{vm.engine!!.park()}
            EngineClient.create(context).use{engine->
                ExportSourceDecoder.open(context,engine,uri,false,edit.toParams(),options){}
                engine.renderExportResolved(request.realisation.film,request.realisation.print).use{full->
                    val sized=SizedRgba16Source.wrap(full,options.customSize){}
                    val converted=OutputConvertedSource.wrap(sized,EditorGrade.from(edit.toParams()),"sRGB",options.colorSpace){}
                    TiffWriter.writeNative(converted,EditorGrade.identity,OutputIcc.generate(options.colorSpace),reference,{})
                }
            }
            if(layout)click("重新导出")else main{vm.retryExportTask(request.jobId);vm.retryExportTask(request.jobId)}
            await("retry"){!vm.isExporting && !vm.exportTasks.loading}
            val entry=store.entries().single{it.id==request.jobId}
            check(entry.state==ExportJobStore.State.SUCCEEDED && entry.attempts==2){entry.toString()+" / "+vm.exportTasks.error}
            check(entry.request==request && media().size==initial.size+1)
            val result=Uri.parse(entry.destination!!.removePrefix("相册: "))
            val actual=context.contentResolver.openInputStream(result)!!.use{it.readBytes()}
            check(strip(actual).contentEquals(strip(reference.toByteArray()))){"Frozen grain/glare seeds or output pixels changed on retry"}
            context.contentResolver.query(result,arrayOf(MediaStore.Images.Media.DISPLAY_NAME),null,null,null)!!.use{
                check(it.moveToFirst() && it.getString(0).contains("1999-12-31")){"Retry used today's date instead of frozen date"}
            }
            main{vm.retryExportTask(request.jobId)};Thread.sleep(250);check(media().size==initial.size+1)
            if(layout){main{vm.exportTasks.refresh()};await("success row"){!vm.exportTasks.loading};capture("success")}
            val changed=failed();source.appendBytes(byteArrayOf(99));val before=media()
            main{vm.exportTasks.refresh()};await("changed row"){!vm.exportTasks.loading}
            main{vm.retryExportTask(changed.jobId)};await("changed reject"){!vm.isExporting && !vm.exportTasks.loading}
            check(media()==before && store.entries().single{it.id==changed.jobId}.state==ExportJobStore.State.FAILED)
            check(vm.exportTasks.error?.contains("改变")==true){vm.exportTasks.error.orEmpty()}
            if(layout)capture("source-changed")
            source.writeBytes(originalBytes)
            val appChanged=failed().copy(appIdentity="0".repeat(64));store.write(appChanged,ExportJobStore.State.FAILED,error="audit old APK")
            main{vm.exportTasks.refresh()};await("old app"){!vm.exportTasks.loading};main{vm.retryExportTask(appChanged.jobId)}
            await("app mismatch"){!vm.isExporting && !vm.exportTasks.loading};check(media()==before && vm.exportTasks.error?.contains("更新")==true)
            if(layout){capture("app-changed");click("返回");check(!vm.exportTasks.opened);capture("returned")}
            "R10 actual failed task retries exact TIFF16 pixels with original grain/glare seeds, ProPhoto/Deflate/317px and1999 frozen naming date; double-click/committed duplicate rejected; changed source/APK publish nothing; layout=$layout"
        } finally {
            (media()-initial).forEach{context.contentResolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,it),null,null)}
            records.forEach{id->runCatching{store.remove(id)}}
            main{vm.exportTasks.close();activity.finish()};source.delete()
        }
    }
}
