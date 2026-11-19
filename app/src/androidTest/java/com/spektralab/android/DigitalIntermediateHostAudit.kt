package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.geometry.FinishedCrop
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import com.spektralab.android.engine.host.RenderHostClient
import com.spektralab.android.feature.editor.EditorViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Independently rendered Cineon U16 densities through the isolated host and durable UI pipeline. */
internal class DigitalIntermediateHostAudit(private val test:Instrumentation,private val output:File) {
    private val context=test.targetContext
    private fun source():File=File(output,"DI_host_${System.nanoTime()}.png").also{file->
        val bitmap=Bitmap.createBitmap(601,403,Bitmap.Config.ARGB_8888)
        try{bitmap.setPixels(IntArray(601*403){i->0xff000000.toInt() or ((i%601*210/601+20)shl 16) or ((i/601*200/403+20)shl 8) or ((i*11)and 255)},0,601,0,0,601,403)
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    private fun edit()=EditorParams().apply {
        grainActive=false;glareActive=false;halationActive=false;exposureEv=.35f;quarterTurns=1;flipH=true
        finishedCrop=FinishedCrop(.1f,.1f,.7f,.7f);brightness=17f;vignetteAmount=63f
    }
    private fun options(res:Int=0)=ExportOptions("TIFF",res,"sRGB",95,true,false,true)
    private fun reference(file:File,edit:EditorParams,options:ExportOptions):Pair<ShortArray,Pair<Int,Int>> = EngineClient.create(context).use{engine->
        ExportSourceDecoder.open(context,engine,Uri.fromFile(file),file.extension.lowercase()in listOf("nef","dng"),edit,options){}
        engine.renderDigitalIntermediate().use{result->
            ShortArray(result.width*result.height*4).also{result.readRows(0,result.height,it)} to (result.width to result.height)
        }
    }
    fun native():String=runBlocking {
        val bitmap=source();var pixels=0L
        try {
            val files=listOf(bitmap,File(output,"fixture.dng"))
            for(file in files) {
                check(file.isFile)
                val p=edit();val opts=options(317);val(expected,size)=reference(file,p,opts)
                ExportSourceSnapshot.create(context,Uri.fromFile(file),false,{}).use{snapshot->
                    val request=ExportFrameRequest(UUID.randomUUID().toString(),snapshot.sha256,file.name,file.extension=="dng",
                        EditSnapshot.capture(p),opts,RawDecoder.decoderIdentity,EngineClient.buildInfo(),appIdentity=ExportRuntimeIdentity.current(context),artifact=ExportArtifact.DIGITAL_INTERMEDIATE)
                    check(ExportFrameRequest.fromJson(request.toJson())==request)
                    val nativeAgain=EngineClient.create(context).use {engine->
                        ExportSourceDecoder.open(context,engine,Uri.fromFile(file),file.extension=="dng",p,opts){}
                        engine.renderDigitalIntermediateResolved(request.realisation.film,request.realisation.print).use{result->
                            ShortArray(expected.size).also{result.readRows(0,result.height,it)}}
                    }
                    check(nativeAgain.contentEquals(expected)){"Resolved DI changed neutral original samples"}
                    val old=JSONObject(request.toJson()).put("schema",5).apply{remove("artifact")}
                    check(ExportFrameRequest.fromJson(old.toString()).artifact==ExportArtifact.FINISHED)
                    check(runCatching{ExportFrameRequest.fromJson(JSONObject(request.toJson()).put("artifact","FUTURE").toString())}.isFailure)
                    var pid=0
                    val client=RenderHostClient(context).apply{onHostStarted={pid=it}}
                    client.render(request,snapshot.descriptor,{}, {_,_->}).use{result->
                        check(pid>0 && pid!=android.os.Process.myPid() && result.width==size.first && result.height==size.second)
                        val actual=ShortArray(expected.size);result.readRows(0,result.height,actual)
                        check(actual.contentEquals(expected)){"Isolated DI changed Cineon16 samples"}
                        for(y in listOf(result.height-1,0,result.height/2)) {
                            val row=ShortArray(result.width*4);result.readRows(y,1,row)
                            check(row.contentEquals(expected.copyOfRange(y*row.size,(y+1)*row.size)))
                        }
                        pixels+=result.width.toLong()*result.height
                    }
                    var cancel=false
                    check(runCatching{client.render(request.copy(jobId=UUID.randomUUID().toString()),snapshot.descriptor,{check(!cancel){"cancel-di"}},{stage,_->if(stage=="rendering")cancel=true})}
                        .exceptionOrNull()?.message?.contains("cancel")==true)
                }
            }
            "DI isolated PID: $pixels PNG/DNG oriented Cineon pixels/arbitrary rows exactly match independent native DI; frozen artifact versioning, future rejection and actual cancellation passed"
        }finally{bitmap.delete()}
    }
    private fun await(label:String,ready:()->Boolean){val start=SystemClock.elapsedRealtime();while(!ready()){check(SystemClock.elapsedRealtime()-start<180000){label};Thread.sleep(40)};test.waitForIdleSync()}
    private fun media()=context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf("_id"),null,null,null)!!.use{c->buildSet{while(c.moveToNext())add(c.getLong(0))}}
    fun raw():String=runBlocking {
        val file=File(output,"pc-parity-DSC_5099.NEF");check(file.isFile)
        val p=EditorParams().apply{grainActive=true;glareActive=false;halationActive=true}
        val opts=options(0)
        val expected=File(output,"di-host-raw-reference.tiff")
        val actual=File(output,"di-host-raw-actual.tiff")
        ExportSourceSnapshot.create(context,Uri.fromFile(file),false,{}).use{snapshot->
            val request=ExportFrameRequest(UUID.randomUUID().toString(),snapshot.sha256,file.name,true,
                EditSnapshot.capture(p),opts,RawDecoder.decoderIdentity,EngineClient.buildInfo(),appIdentity=ExportRuntimeIdentity.current(context),artifact=ExportArtifact.DIGITAL_INTERMEDIATE)
            EngineClient.create(context).use{engine->
                ExportSourceDecoder.open(context,engine,Uri.fromFile(file),true,p,opts){}
                engine.renderDigitalIntermediateResolved(request.realisation.film,request.realisation.print).use{result->
                    check(result.width==6064 && result.height==4040)
                    expected.outputStream().use{TiffWriter.writeNative(result,EditorGrade.identity,null,it,{})}
                }
            }
            var hostPid=0
            RenderHostClient(context).apply{onHostStarted={hostPid=it}}.render(request,snapshot.descriptor,{}, {_,_->}).use{result->
                check(hostPid!=android.os.Process.myPid() && result.width==6064 && result.height==4040)
                actual.outputStream().use{TiffWriter.writeNative(result,EditorGrade.identity,null,it,{})}
            }
            val hashA=java.security.MessageDigest.getInstance("SHA-256");val hashB=java.security.MessageDigest.getInstance("SHA-256")
            fun digest(file:File,hash:java.security.MessageDigest):String{file.inputStream().use{input->val buffer=ByteArray(65536)
                while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}};return hash.digest().joinToString(""){"%02x".format(it)}}
            val sha=digest(expected,hashA);check(digest(actual,hashB)==sha){"Full original NEF DI host pixels changed"}
            File(output,"di-host-raw.json").writeText(JSONObject().put("width",6064).put("height",4040).put("bytes",actual.length()).put("tiff_sha256",sha).toString())
            "Original6064x4040 NEF with resolved grain: independent and isolated host full TIFF16 byteSHA match;73495680 RGB samples, noICC/no downscale"
        }
    }
    fun application(layout:Boolean=false):String {
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))as MainActivity
        lateinit var vm:EditorViewModel;test.runOnMainSync{vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        val file=source();val before=media();val store=ExportJobStore(context);val ownedIds=mutableListOf<String>()
        try {
            await("engine"){vm.engine!=null && !vm.busy};val p=edit();val uri=Uri.fromFile(file);EditStore.save(context,uri,p)
            test.runOnMainSync{vm.dismissError();vm.smartAutoEnabled=false;vm.openPhotos(listOf(uri))}
            await("source"){vm.errorMessage!=null || vm.canPickNeutral && !vm.busy};check(vm.errorMessage==null)
            test.runOnMainSync{vm.engine!!.park()};val(expected,size)=reference(file,p,options(0))
            val old=store.entries().map{it.id}.toSet()
            test.runOnMainSync{vm.exportFormat="HEIF";vm.exportColorSpace="ProPhoto RGB";vm.exportCustomSize=ExportSize(ExportSize.Kind.LONG_EDGE,31.0);vm.exportTiffDeflate=true;vm.exportRes=0;vm.exportDi()}
            await("export DI"){!vm.isExporting};check(vm.errorMessage==null){vm.errorMessage.orEmpty()}
            val entry=store.entries().single{it.id !in old};ownedIds+=entry.id
            check(entry.request?.artifact==ExportArtifact.DIGITAL_INTERMEDIATE && entry.state==ExportJobStore.State.SUCCEEDED)
            check(entry.request!!.options.customSize==null && !entry.request.options.tiffDeflate && entry.request.options.colorSpace=="sRGB")
            val result=Uri.parse(entry.destination!!.removePrefix("相册: "));val bytes=context.contentResolver.openInputStream(result)!!.use{it.readBytes()}
            val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);val n=b.getShort(8).toInt()and 65535
            val fields=(0 until n).associate{at->val pos=10+at*12;(b.getShort(pos).toInt()and 65535)to b.getInt(pos+8)}
            check(fields[256]==size.first && fields[257]==size.second && 34675 !in fields && fields[259]==1)
            val payload=ByteBuffer.wrap(bytes,fields.getValue(273),bytes.size-fields.getValue(273)).order(ByteOrder.LITTLE_ENDIAN)
            for(i in 0 until size.first*size.second)for(c in 0..2)check(payload.short==expected[i*4+c])
            check(media().size==before.size+1 && vm.log.contains("DI 导出:"))
            if(layout) {
                test.runOnMainSync{vm.exportTasks.open()};await("DI receipt screen"){!vm.exportTasks.loading && vm.exportTasks.entries.any{it.id==entry.id}}
                Thread.sleep(500);test.waitForIdleSync()
                val screenshot=test.uiAutomation.takeScreenshot()!!
                try{File(output,"di-host-task.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{screenshot.recycle()}
                check(vm.exportTasks.entries.first{it.id==entry.id}.request!!.artifact==ExportArtifact.DIGITAL_INTERMEDIATE)
                test.runOnMainSync{vm.exportTasks.close()}
            }
            test.runOnMainSync{vm.exportDi();vm.cancelRender()};await("cancel DI"){!vm.isExporting};check(media().size==before.size+1)
            if(!layout) {
                val frozen=entry.request.copy(jobId=UUID.randomUUID().toString())
                ownedIds+=frozen.jobId
                store.write(frozen,ExportJobStore.State.FAILED,error="DI retry fixture",sourceReference=uri.toString())
                test.runOnMainSync{vm.params.filmOff=true;vm.params.exposureEv=3f;vm.exportTasks.open()}
                await("retry receipt"){!vm.exportTasks.loading && vm.exportTasks.entries.any{it.id==frozen.jobId}}
                test.runOnMainSync{vm.retryExportTask(frozen.jobId);vm.retryExportTask(frozen.jobId)}
                await("retry DI"){!vm.isExporting && !vm.exportTasks.loading}
                val retried=store.entries().single{it.id==frozen.jobId}
                check(retried.state==ExportJobStore.State.SUCCEEDED && retried.attempts==2){vm.exportTasks.error.orEmpty()}
                check(retried.request==frozen && media().size==before.size+2)
                val retryBytes=context.contentResolver.openInputStream(Uri.parse(retried.destination!!.removePrefix("相册: ")))!!.use{it.readBytes()}
                check(retryBytes.contentEquals(bytes)){"Frozen DI retry changed TIFF bytes"}
                test.runOnMainSync{vm.exportTasks.close();vm.params.filmOff=false}
            }
            test.runOnMainSync{vm.params.filmOff=true;vm.exportDi()};check(!vm.isExporting)
            val beforeDemo=media();test.runOnMainSync{vm.params.filmOff=false;vm.exportFormat="PNG";vm.openTestImage()};await("demo"){!vm.busy && vm.lastResult!=null}
            test.runOnMainSync{vm.exportDi()};await("demo DI"){!vm.isExporting};check(vm.errorMessage==null && media().size==beforeDemo.size+1)
            return "Actual source DI UI uses isolated host/durable original-source job, exact full Cineon16 samples/metadata/noICC; finished grade/crop/file-space/custom-size/compression not applied; cancel publishes nothing; film bypass rejects DI; generated demo legacy path preserved;layout=$layout"
        }finally{
            (media()-before).forEach{context.contentResolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,it),null,null)}
            ownedIds.forEach{runCatching{store.remove(it)}};test.runOnMainSync{vm.exportFormat="JPEG";vm.exportColorSpace="sRGB";vm.exportCustomSize=null;vm.exportTiffDeflate=false;activity.finish()};file.delete()
        }
    }
}
