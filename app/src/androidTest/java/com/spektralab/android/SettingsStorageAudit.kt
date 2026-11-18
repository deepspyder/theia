package com.spektralab.android

import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Context
import android.net.Uri
import android.system.Os
import com.spektralab.android.core.model.AppSettings
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.cache.RenderDiskCache
import com.spektralab.android.data.diagnostics.DiagnosticReport
import com.spektralab.android.data.local.AppSettingsStore
import com.spektralab.android.engine.Rgba16Source
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** Isolated real files, active readers, cancelled writes and explicit diagnostic-field inspection. */
internal class SettingsStorageAudit(private val test: Instrumentation,private val output: File) {
    fun run(): String {
        val root=File(test.targetContext.cacheDir,"r05-storage-${System.nanoTime()}").apply { mkdirs() }
        val document=File(root,"app-settings.json")
        val store=AppSettingsStore(root)
        try {
            check(store.read().value==AppSettings() && !document.exists())
            val kept=AppSettings(1024,64,"kodak_gold_200","kodak_ektacolor_edge")
            document.writeText(JSONObject().put("preview_long_edge",1600).put("future_extension",JSONObject().put("keep",true)).toString())
            check(store.read().value.previewLongEdge==1600)
            store.save(kept)
            check(AppSettingsStore(root).read().value==kept)
            check(JSONObject(document.readText()).getJSONObject("future_extension").getBoolean("keep"))
            val valid=document.readBytes()
            for (bad in listOf("[]","{", "{\"version\":2}","{\"preview_long_edge\":true}",
                "{\"preview_long_edge\":1024.5}","{\"render_cache_mib\":999999999999}",
                "{\"default_film\":\"../../private\"}","x".repeat(65537))) {
                document.writeText(bad)
                check(store.read().unavailable)
                check(runCatching { store.save(kept) }.isFailure)
                check(document.readText()==bad) { "Bad/future settings overwritten" }
            }
            document.writeBytes(valid)
            Os.chmod(root.path,320) // 0500: read/search allowed, creating an atomic replacement is not.
            try {
                check(runCatching { store.save(kept.copy(previewLongEdge=2048)) }.isFailure)
                check(document.readBytes().contentEquals(valid))
            } finally { Os.chmod(root.path,448) }
            val pixels=object:Rgba16Source {
                override val width=17
                override val height=7
                override fun readRows(y:Int,rows:Int,destination:ShortArray) {
                    repeat(width*rows*4) { destination[it]=((y*width*4+it)*31).toShort() }
                }
            }
            val directory=File(root,"frames")
            val bytes=68L+17*7*8
            val cache=RenderDiskCache(directory,bytes*4)
            val a="a".repeat(64);val b="b".repeat(64)
            check(cache.put(a,pixels) && cache.put(b,pixels))
            val lease=checkNotNull(cache.acquire(a))
            val second=checkNotNull(cache.acquire(a))
            check(cache.snapshot().readers==2 && cache.snapshot().leasedBytes==bytes)
            val clear=cache.clear()
            check(clear.freedBytes==bytes && clear.retained.bytes==bytes && clear.retained.readers==2)
            cache.setBudget(0)
            check(cache.snapshot().bytes==bytes && !cache.put(b,pixels))
            val actual=ShortArray(17*7*4);val expected=ShortArray(actual.size)
            lease.readRows(0,7,actual);pixels.readRows(0,7,expected)
            check(actual.contentEquals(expected))
            lease.close();check(cache.snapshot().bytes==bytes)
            second.close();check(cache.snapshot().bytes==0L)
            cache.setBudget(bytes*2)
            check(cache.put(a,pixels))
            var cancelled=false
            try { cache.put(b,pixels) { throw kotlinx.coroutines.CancellationException("cancel") } }
            catch (_:kotlinx.coroutines.CancellationException) { cancelled=true }
            check(cancelled && cache.snapshot().entries==1 && directory.listFiles()!!.none { it.extension=="partial" })
            val source=File(root,"private-DSC-source.NEF").apply { writeText("GPS secret content://private /sdcard/personal") }
            val database=File(directory,"saved-photos.db").apply { writeText("edits") }
            Os.chmod(directory.path,0)
            try {check(runCatching { cache.clear() }.exceptionOrNull() is java.io.IOException)}
            finally {Os.chmod(directory.path,448)}
            val link=File(directory,"${"f".repeat(64)}.rgba16")
            Os.symlink(source.path,link.path)
            cache.clear()
            check(source.readText().contains("GPS secret") && database.readText()=="edits" && link.exists())
            link.delete()
            val cacheRoot=File(root,"isolated-app-cache").apply {mkdirs()}
            val wrapper=object:ContextWrapper(test.targetContext) {override fun getCacheDir()=cacheRoot}
            val constructor=ExportRenderCache::class.java.getDeclaredConstructor(Context::class.java).apply {isAccessible=true}
            val optional=constructor.newInstance(wrapper)
            optional.storage()
            val rendered=File(cacheRoot,"export-rgba16-v1")
            Os.chmod(rendered.path,0)
            try {
                // Optional cache I/O failure cannot turn a completed render into an export error.
                optional.store(ExportRenderCache.Request(a,"irrelevant"),Uri.fromFile(source),pixels) {}
            } finally {Os.chmod(rendered.path,448)}
            val archive=DiagnosticReport.create(test.targetContext,kept,cache.snapshot(),ExportRenderCache.Stats(2,3,4,5),
                DiagnosticReport.Workspace(2,true,true,1024,683,false))
            val entries=linkedMapOf<String,ByteArray>()
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while(true) { val entry=zip.nextEntry ?: break;entries[entry.name]=zip.readBytes() }
            }
            check(entries.keys==setOf("report.json","README.txt") && archive.size<=65536)
            val text=checkNotNull(entries["report.json"]).toString(Charsets.UTF_8)
            for(secret in listOf("content://","/storage/","/sdcard/","/data/user/","private-DSC","GPS secret","ANDROID_ID","serial","fingerprint"))
                check(!text.contains(secret,true)) { "Diagnostic leak: $secret" }
            val report=JSONObject(text)
            check(report.keys().asSequence().toSet()==setOf("format","app","device","settings","render_cache","workspace"))
            check(report.getJSONObject("device").getJSONArray("supported_abis").length()>0)
            check(report.getJSONObject("render_cache").getLong("hits_this_process")==2L)
            var checks=0
            check(runCatching { DiagnosticReport.create(test.targetContext,kept,cache.snapshot(),ExportRenderCache.Stats(),
                DiagnosticReport.Workspace()) { if(++checks==2) throw kotlinx.coroutines.CancellationException() } }.isFailure)
            File(output,"r05-diagnostics.zip").writeBytes(archive)
            return "Atomic settings/v0/default/reopen/unknown keys, eight corrupt/future/oversize rejections and real unwritable storage; two active cache readers survive clear/zero budget U16-exact, close trims, cancelled writer/symlink/source/database safe; bounded two-entry diagnostic ZIP allowlist and cancellation passed"
        } finally {
            Os.chmod(root.path,448)
            // Only paths created inside this audit's unique directory; symlink already unlinked.
            root.walkBottomUp().forEach(File::delete)
        }
    }
}
