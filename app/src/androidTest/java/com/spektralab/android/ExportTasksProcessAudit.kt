package com.spektralab.android

import android.app.Instrumentation
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real process termination at the host rendering callback, followed by fresh-process immutable retry. */
internal class ExportTasksProcessAudit(private val test:Instrumentation,private val output:File) {
    private val context=test.targetContext
    private val marker=File(output,"r10-process.json")
    private val expected=File(output,"r10-process-reference.tiff")
    private fun media()=context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf("_id"),null,null,null)!!.use{c->buildSet{while(c.moveToNext())add(c.getLong(0))}}
    private fun await(label:String,ready:()->Boolean){val start=SystemClock.elapsedRealtime();while(!ready()){check(SystemClock.elapsedRealtime()-start<180000){label};Thread.sleep(40)};test.waitForIdleSync()}
    fun seed(worker:Boolean):String=runBlocking {
        val source=File(output,"R10_process_photo.jpg")
        val photo=test.context.assets.open("13mb.jpg").use{BitmapFactory.decodeStream(it)}!!
        val small=Bitmap.createScaledBitmap(photo,1601,(photo.height.toLong()*1601/photo.width).toInt(),true)
        try{source.outputStream().use{small.compress(Bitmap.CompressFormat.JPEG,95,it)}}finally{small.recycle();photo.recycle()}
        val edit=EditSnapshot.capture(EditorParams().apply{grainActive=true;glareActive=true;brightness=5f;vignetteAmount=31f})
        val options=ExportOptions("TIFF",0,"sRGB",95,false,false,true,ExportSize(ExportSize.Kind.LONG_EDGE,451.0))
        var killed=false
        val result=runCatching {
            ExportCoordinator.get(context).run(Uri.fromFile(source),source.name,edit,options,{}){task,snapshot->
                EngineClient.create(context).use{engine->
                    ExportSourceDecoder.open(context,engine,Uri.fromFile(source),false,edit.toParams(),options){}
                    engine.renderExportResolved(task.request.realisation.film,task.request.realisation.print).use {full->
                        val sized=SizedRgba16Source.wrap(full,options.customSize){}
                        expected.outputStream().use{TiffWriter.writeNative(sized,EditorGrade.from(edit.toParams()),null,it,{})}
                    }
                }
                marker.writeText(JSONObject().put("request",JSONObject(task.request.toJson())).put("source",source.path)
                    .put("media",JSONArray(media().toList())).put("worker",worker).toString())
                task.render(snapshot,{}){stage,_->if(stage=="rendering" && !killed){
                    if(worker){
                        val manager=context.getSystemService(ActivityManager::class.java)
                        val pid=manager.runningAppProcesses.single{it.processName==context.packageName+":render"}.pid
                        check(pid!=Process.myPid());killed=true;Process.killProcess(pid)
                    }else {
                        marker.writeText(JSONObject(marker.readText()).put("kill_requested",true).toString())
                        Process.killProcess(Process.myPid())
                    }
                }}.use{error("Expected process termination was missed")}
            }
        }
        check(worker && killed && result.isFailure){"Main process termination was missed"}
        check(result.exceptionOrNull() is ExportFailure && (result.exceptionOrNull() as ExportFailure).code==ExportFailureCode.HOST_DIED) {
            "Expected an actual binder death, got ${result.exceptionOrNull()}"
        }
        verify()
    }
    fun verify():String {
        val j=JSONObject(marker.readText());val request=ExportFrameRequest.fromJson(j.getJSONObject("request").toString())
        check(j.getBoolean("worker") || j.optBoolean("kill_requested")){"Main process kill callback was not reached"}
        val before=j.getJSONArray("media").let{a->(0 until a.length()).map{a.getLong(it)}.toSet()}
        check(media()==before){"A killed process published a file"}
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel;test.runOnMainSync{vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        val store=ExportJobStore(context)
        try {
            await("engine"){vm.engine!=null && !vm.busy}
            test.runOnMainSync{vm.exportTasks.open()}
            await("recovered"){!vm.exportTasks.loading && vm.exportTasks.entries.any{it.id==request.jobId}}
            val recovered=vm.exportTasks.entries.single{it.id==request.jobId}
            check(recovered.state==ExportJobStore.State.FAILED && recovered.retryable && recovered.request==request)
            test.runOnMainSync{vm.retryExportTask(request.jobId)}
            await("process retry"){!vm.isExporting && !vm.exportTasks.loading}
            val final=store.entries().single{it.id==request.jobId};check(final.state==ExportJobStore.State.SUCCEEDED){final.toString()+vm.exportTasks.error}
            check(final.request==request && final.attempts==2 && media().size==before.size+1)
            val uri=Uri.parse(final.destination!!.removePrefix("相册: "))
            val actual=context.contentResolver.openInputStream(uri)!!.use{it.readBytes()}
            File(output,"r10-process-actual.tiff").writeBytes(actual)
            fun pixels(bytes:ByteArray):ByteArray{val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);val n=b.getShort(8).toInt() and 65535
                val entry=(0 until n).map{10+it*12}.first{b.getShort(it).toInt() and 65535==273};return bytes.copyOfRange(b.getInt(entry+8),bytes.size)}
            check(pixels(actual).contentEquals(pixels(expected.readBytes()))){"Process recovery changed original random realization or graded pixels"}
            return "Actual ${if(j.getBoolean("worker"))"render-worker death" else "main-process kill during host rendering"}: no partial publication; durable request/source/random seeds survive; explicit fresh retry publishes one exact TIFF16 result"
        }finally{
            (media()-before).forEach{context.contentResolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,it),null,null)}
            runCatching{store.remove(request.jobId)};test.runOnMainSync{vm.exportTasks.close();activity.finish()};File(j.getString("source")).delete()
        }
    }
}
