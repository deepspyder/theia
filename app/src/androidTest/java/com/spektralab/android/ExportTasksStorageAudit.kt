package com.spektralab.android

import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.engine.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Isolated durable state, real pending/committed MediaStore receipts, no production fault hooks. */
internal class ExportTasksStorageAudit(private val test:Instrumentation,private val output:File) {
    fun run():String {
        val dir=File(test.targetContext.cacheDir,"r10-store-${System.nanoTime()}").apply{mkdirs()}
        val context=object:ContextWrapper(test.targetContext){override fun getApplicationContext()=this;override fun getFilesDir()=dir}
        val store=ExportJobStore(context)
        val source=File(dir,"source.nef").apply{writeBytes(ByteArray(317){it.toByte()})}
        val copy=File(dir,"copy.nef").apply{source.copyTo(this)}
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)}
        val reference=Uri.fromFile(source).toString()
        val owned=mutableListOf<Uri>()
        fun request()=ExportFrameRequest(UUID.randomUUID().toString(),hash,source.name,true,
            EditSnapshot.capture(EditorParams().apply{exposureEv=1.25f}),ExportOptions("TIFF",0,"ProPhoto RGB",95,false,false,true,tiffDeflate=true),
            RawDecoder.decoderIdentity,EngineClient.buildInfo(),submittedAt=1795020205000L,namingDate="2026-11-19",appIdentity=ExportRuntimeIdentity.current(context))
        fun path(id:String)=File(dir,"export-jobs/$id.json")
        fun reserve(pending:Boolean)=test.targetContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply{
            put(MediaStore.Images.Media.DISPLAY_NAME,"Theia_Audit_r10_${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.IS_PENDING,if(pending)1 else 0)
        })!!.also{owned+=it}
        try {
            val a=request();store.write(a,ExportJobStore.State.RENDERING,sourceReference=reference)
            store.recover();val first=store.entries().single();check(first.state==ExportJobStore.State.FAILED && first.retryable)
            check(first.request==a && first.sourceReference==reference)
            val claim=store.claimRetry(a.jobId);check(claim.request==a)
            val queued=store.entries().single();check(queued.state==ExportJobStore.State.QUEUED && queued.attempts==2)
            check(runCatching{ExportJobStore(context).claimRetry(a.jobId)}.isFailure)
            store.write(a,ExportJobStore.State.FAILED,error="again");check(store.entries().single().sourceReference==reference)
            val wait=CountDownLatch(1);val passes=AtomicInteger()
            val threads=(0..1).map{Thread{wait.await();runCatching{ExportJobStore(context).claimRetry(a.jobId)}.onSuccess{passes.incrementAndGet()}}.apply{start()}}
            wait.countDown();threads.forEach{it.join()};check(passes.get()==1)
            store.write(a,ExportJobStore.State.CANCELLED,error="cancel")
            store.relink(a.jobId,Uri.fromFile(copy));check(store.entries().single().sourceReference==Uri.fromFile(copy).toString())
            copy.appendBytes(byteArrayOf(1));val unchanged=path(a.jobId).readBytes()
            check(runCatching{store.relink(a.jobId,Uri.fromFile(copy))}.isFailure && path(a.jobId).readBytes().contentEquals(unchanged))
            copy.writeBytes(source.readBytes())
            val frozen=ExportFrameRequest.fromJson(a.toJson());check(frozen==a && frozen.submittedAt==1795020205000L && frozen.namingDate=="2026-11-19")
            for(schema in 1..4){val old=JSONObject(a.toJson()).put("schema",schema).apply{remove("submitted_at");remove("naming_date")}
                val legacy=ExportFrameRequest.fromJson(old.toString());check(legacy.submittedAt==0L && legacy.namingDate.isEmpty())}
            for(key in listOf("submitted_at","naming_date")){val bad=JSONObject(a.toJson()).put(key,JSONObject())
                check(runCatching{ExportFrameRequest.fromJson(bad.toString())}.isFailure)}

            val committed=request();val published=reserve(false)
            store.write(committed,ExportJobStore.State.FAILED,published.toString(),"receipt write interrupted",reference)
            check(runCatching{store.claimRetry(committed.jobId)}.isFailure)
            check(store.entries().single{it.id==committed.jobId}.state==ExportJobStore.State.SUCCEEDED)
            val pending=request();val pendingUri=reserve(true)
            store.write(pending,ExportJobStore.State.FAILED,pendingUri.toString(),"cleanup interrupted",reference)
            store.claimRetry(pending.jobId)
            check(test.targetContext.contentResolver.query(pendingUri,arrayOf("_id"),null,null,null)?.use{it.moveToFirst()}!=true)
            store.write(pending,ExportJobStore.State.CANCELLED)
            val backup=request();store.write(backup,ExportJobStore.State.RENDERING,sourceReference=reference)
            check(path(backup.jobId).renameTo(File(path(backup.jobId).path+".bak")))
            store.recover();check(store.entries().single{it.id==backup.jobId}.state==ExportJobStore.State.FAILED)
            val malformed=request();val badFile=path(malformed.jobId);badFile.writeText("{broken")
            val future=request();val futureFile=path(future.jobId);futureFile.writeText(JSONObject().put("journal_version",99).put("keep",17).toString())
            val futureBytes=futureFile.readBytes();store.recover()
            check(store.entries().single{it.id==malformed.jobId}.request==null && store.entries().single{it.id==future.jobId}.request==null)
            check(futureFile.readBytes().contentEquals(futureBytes) && runCatching{store.write(future,ExportJobStore.State.FAILED)}.isFailure)
            val unknown=JSONObject(path(a.jobId).readText()).put("unowned",JSONObject().put("keep",7));path(a.jobId).writeText(unknown.toString())
            store.write(a,ExportJobStore.State.FAILED);check(JSONObject(path(a.jobId).readText()).getJSONObject("unowned").getInt("keep")==7)
            val noSpaceRequest=request();val huge=path(noSpaceRequest.jobId);huge.writeBytes(ByteArray(256*1024+1))
            check(store.entries().single{it.id==noSpaceRequest.jobId}.request==null)
            store.remove(a.jobId);check(!path(a.jobId).exists() && source.isFile && copy.isFile)
            val remains=test.targetContext.contentResolver.query(published,arrayOf("_id"),null,null,null)?.use{it.moveToFirst()}==true
            store.remove(committed.jobId);check(remains && source.isFile && test.targetContext.contentResolver.query(published,arrayOf("_id"),null,null,null)?.use{it.moveToFirst()}==true)
            return "R10: durable immutable retry/options/seeds/name/date, single cross-instance claim, actual committed dedupe/pending cleanup, identical-source relink, legacy+backup/crash/future/corrupt/oversize/unknown-field protection; remove touches receipts only"
        }finally{owned.forEach{runCatching{test.targetContext.contentResolver.delete(it,null,null)}};dir.deleteRecursively()}
    }
}
