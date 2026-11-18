package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.AppSettings
import com.spektralab.android.core.model.EditSnapshot
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.local.AppSettingsStore
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.settings.SettingsViewModel
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONObject

internal class SettingsUiAudit(private val test: Instrumentation,private val output: File) {
    private val context=test.targetContext
    private val store=AppSettingsStore.get(context)
    private val settingsFile=File(context.filesDir,"app-settings.json")
    private fun main(action:()->Unit)=test.runOnMainSync(action)
    private fun await(label:String,condition:()->Boolean) {
        val start=SystemClock.elapsedRealtime()
        while(!condition()) { check(SystemClock.elapsedRealtime()-start<120000) { "Settings timeout: $label" };Thread.sleep(40) }
        test.waitForIdleSync()
    }
    private fun idle(vm:EditorViewModel) {
        await("editor ${vm.errorMessage}") { vm.errorMessage!=null || vm.hasPhoto && vm.canPickNeutral && !vm.isExporting && !vm.isBatchExporting }
        check(vm.errorMessage==null) { vm.errorMessage.orEmpty() }
    }
    private fun restoreSettings(bytes:ByteArray?) {
        if(bytes==null) settingsFile.delete() else {
            val atomic=AtomicFile(settingsFile);val stream=atomic.startWrite()
            try {stream.write(bytes);atomic.finishWrite(stream)} catch(error:Throwable){atomic.failWrite(stream);throw error}
        }
        val read=store.read();check(!read.unavailable)
        ExportRenderCache.get(context).setBudget(read.value.renderCacheBytes)
    }
    fun run(mode:String):String {
        if(mode=="settings-process") return process()
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var editor:EditorViewModel
        lateinit var settings:SettingsViewModel
        main {editor=ViewModelProvider(activity)[EditorViewModel::class.java];settings=ViewModelProvider(activity)[SettingsViewModel::class.java]}
        await("startup"){editor.engine!=null && !editor.busy && !settings.busy && editor.films.isNotEmpty()}
        val original=settingsFile.takeIf(File::exists)?.readBytes()
        val originalAuto=editor.smartAutoEnabled
        var keepForProcess=false
        try {
            check(!settings.unavailable)
            if(mode=="settings-ui") return ui(settings)
            main {editor.smartAutoEnabled=false}
            val film=editor.films.first {it.monochrome}
            val paper=editor.papers.first {it.monochrome}
            val desired=AppSettings(1024,0,film.id,paper.id)
            main {settings.update(desired)};await("save"){!settings.busy};check(store.snapshot().value==desired)
            val file=File(output,"r05-photo-${System.nanoTime()}.png")
            val bitmap=Bitmap.createBitmap(2081,1301,Bitmap.Config.ARGB_8888)
            try {bitmap.eraseColor(0xff738594.toInt());file.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
            finally {bitmap.recycle()}
            val uri=Uri.fromFile(file)
            main {editor.openPhotos(listOf(uri))};idle(editor)
            check(editor.params.filmId==film.id && editor.params.paperId==paper.id)
            check(maxOf(editor.previewWidth(),editor.previewHeight())==1024)
            main {editor.params.grainActive=false;editor.params.glareActive=false;editor.params.halationActive=false;editor.scheduleRender()};idle(editor)
            val saved=EditSnapshot.capture(editor.params)
            fun export(expectedWidth: Int = 2081,expectedHeight: Int = 1301):String {
                main {editor.exportFormat="PNG";editor.exportRes=0;editor.exportCustomSize=null;editor.exportKeepCapture=false;editor.exportKeepLocation=false;editor.export()}
                await("export"){!editor.isExporting};check(editor.errorMessage==null) {editor.errorMessage.orEmpty()}
                val result=Uri.parse(editor.log.substringAfterLast("相册: ").lineSequence().first().trim())
                check(result.scheme=="content")
                try {
                    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    context.contentResolver.openInputStream(result).use {BitmapFactory.decodeStream(it,null,bounds)}
                    check(bounds.outWidth==expectedWidth && bounds.outHeight==expectedHeight)
                    val digest=MessageDigest.getInstance("SHA-256")
                    context.contentResolver.openInputStream(result)!!.use {input ->
                        val buffer=ByteArray(64*1024)
                        while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}
                    }
                    return digest.digest().joinToString(""){"%02x".format(it)}
                } finally {context.contentResolver.delete(result,null,null)}
            }
            val before=export()
            val color=editor.films.first {it.id=="kodak_gold_200"}
            main {settings.chooseFilm(color.id)};await("pair"){!settings.busy}
            check(EditSnapshot.capture(editor.params)==saved && store.snapshot().value.defaultFilm==color.id)
            for(edge in listOf(1600,2048)) {
                main {settings.update(settings.value.copy(previewLongEdge=edge,renderCacheMiB=64))};await("budget"){!settings.busy}
                main {editor.openPhoto(uri)};idle(editor)
                check(EditSnapshot.capture(editor.params)==saved && maxOf(editor.previewWidth(),editor.previewHeight())==edge)
            }
            main {settings.clearCache()};await("clear"){!settings.busy}
            val after=export();check(before==after) {"Preview/default/cache preference changed original-size output"}
            val raw=Uri.fromFile(File(output,"perf-z6ii.NEF"))
            val prior=EditStore.load(context,raw)
            try {
                EditStore.save(context,raw,EditorParams().apply {grainActive=false;glareActive=false})
                var rawOutput:String?=null
                val originalSize=com.spektralab.android.engine.RawDecoder.decodeForExport(context,raw,0).use {it.width to it.height}
                for(edge in AppSettings.PREVIEW_EDGES) {
                    main {settings.update(settings.value.copy(previewLongEdge=edge))};await("RAW budget"){!settings.busy}
                    main {editor.openPhotos(listOf(raw))};idle(editor)
                    check(maxOf(editor.previewWidth(),editor.previewHeight())<=edge && maxOf(editor.sourceBitmap!!.width,editor.sourceBitmap!!.height)<=edge)
                    check(editor.currentIsRaw)
                    if(edge==1024) {
                        main {settings.update(settings.value.copy(previewLongEdge=2048))};await("deferred budget"){!settings.busy}
                        main {editor.applyRawWhiteBalancePreset(6200f,7f)};idle(editor)
                        check(maxOf(editor.previewWidth(),editor.previewHeight())==1024) {"New budget applied before reopening RAW"}
                        main {editor.resetCameraWb()};idle(editor)
                    }
                    if(edge==1024 || edge==2048) {
                        val bytes=export(originalSize.first,originalSize.second)
                        if(rawOutput==null)rawOutput=bytes else check(rawOutput==bytes) {"RAW preview budget changed original-size PNG"}
                    }
                }
            } finally {if(prior!=null) EditStore.save(context,raw,prior)}
            main {settings.update(desired.copy(previewLongEdge=1600,renderCacheMiB=64))};await("persist"){!settings.busy}
            val record=JSONObject().put("file",file.path).put("edit",saved.json).put("film",film.id).put("paper",paper.id)
                .put("before_export_sha256",before)
                .put("original_auto",originalAuto).put("original",original?.let {android.util.Base64.encodeToString(it,android.util.Base64.NO_WRAP)} ?: JSONObject.NULL)
            File(output,"r05-process.json").writeText(record.toString())
            keepForProcess=true
            return "New default mono pair applied once; existing edit preserved through defaults change and 1024/1600/2048 preview budgets; original 2081x1301 PNG byte-exact before/after cache clear and changed defaults; real NEF source/editor previews respect all budgets; preferences seeded for process restart"
        } finally {
            if(!keepForProcess){restoreSettings(original);main {editor.smartAutoEnabled=originalAuto}}
            main {activity.finish()}
        }
    }
    private fun process():String {
        val record=JSONObject(File(output,"r05-process.json").readText())
        val original=if(record.isNull("original")) null else android.util.Base64.decode(record.getString("original"),android.util.Base64.NO_WRAP)
        val value=store.read().value
        check(value.previewLongEdge==1600 && value.renderCacheMiB==64 && value.defaultFilm==record.getString("film") && value.defaultPaper==record.getString("paper"))
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel
        main {vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        try {
            await("engine"){vm.engine!=null && !vm.busy}
            main {vm.smartAutoEnabled=false;vm.openPhotos(listOf(Uri.fromFile(File(record.getString("file")))))};idle(vm)
            check(EditSnapshot.capture(vm.params).json==record.getString("edit") && maxOf(vm.previewWidth(),vm.previewHeight())==1600)
            return "Fresh process restores all app preferences; old photo keeps complete saved edit and uses chosen preview budget; original user preferences restored"
        } finally {restoreSettings(original);main {vm.smartAutoEnabled=record.getBoolean("original_auto");activity.finish()}}
    }

    private fun root():AccessibilityNodeInfo? {test.uiAutomation.clearCache();return test.uiAutomation.rootInActiveWindow}
    private fun find(label:String,node:AccessibilityNodeInfo?=root()):AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.text?.toString()==label || node.contentDescription?.toString()==label)return node
        for(i in 0 until node.childCount)find(label,node.getChild(i))?.let {return it}
        return null
    }
    private fun click(label:String) {
        reveal(label)
        fun action(node:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.text?.toString()==label || node.contentDescription?.toString()==label) {
                var parent:AccessibilityNodeInfo?=node
                while(parent!=null){if(parent.isClickable && parent.isEnabled)return parent;parent=parent.parent}
            }
            for(i in 0 until node.childCount)action(node.getChild(i))?.let{return it}
            return null
        }
        val started=SystemClock.elapsedRealtime()
        var node=action(root())
        while(node==null && SystemClock.elapsedRealtime()-started<8000) {Thread.sleep(100);node=action(root())}
        if(node==null) {
            capture("action-failure")
            val tree=StringBuilder()
            fun describe(item:AccessibilityNodeInfo?) {
                if(item==null)return
                tree.append("${item.viewIdResourceName} text=${item.text} desc=${item.contentDescription} click=${item.isClickable} enabled=${item.isEnabled}\n")
                for(i in 0 until item.childCount)describe(item.getChild(i))
            }
            describe(root());File(output,"r05-action-failure.txt").writeText("label=$label\n$tree")
        }
        check(checkNotNull(node) {"No action: $label"}.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        test.waitForIdleSync();Thread.sleep(150)
    }
    private fun reveal(label:String) {
        repeat(30) {
            find(label)?.let {node->
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                test.waitForIdleSync();Thread.sleep(70)
                if(find(label)?.isVisibleToUser==true)return
            }
            fun scroll(node:AccessibilityNodeInfo?):Boolean {
                if(node==null)return false
                if(node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true
                for(i in 0 until node.childCount)if(scroll(node.getChild(i)))return true
                return false
            }
            scroll(root());Thread.sleep(60)
        }
        error("Missing setting: $label")
    }
    private fun capture(name:String) {
        test.waitForIdleSync();Thread.sleep(200)
        val image=checkNotNull(test.uiAutomation.takeScreenshot())
        try{File(output,"r05-ui-$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
    }
    private fun ui(settings:SettingsViewModel):String {
        click("更多操作");click(context.getString(R.string.settings_title))
        await("settings"){!settings.busy && find(context.getString(R.string.settings_preview_title))!=null}
        capture("top")
        click(context.getString(R.string.settings_preview_edge));capture("preview-menu")
        click(context.getString(R.string.settings_pixel_size,1024));await("save"){!settings.busy}
        check(settings.value.previewLongEdge==1024)
        click(context.getString(R.string.settings_film));capture("film-menu")
        click(settings.films.first {it.id=="kodak_gold_200"}.name);await("film"){!settings.busy}
        check(settings.value.defaultFilm=="kodak_gold_200")
        reveal(context.getString(R.string.settings_cache_title));capture("cache")
        click(context.getString(R.string.settings_cache_budget));capture("cache-menu")
        click(context.getString(R.string.settings_cache_off));await("budget"){!settings.busy}
        check(settings.value.renderCacheMiB==0)
        click(context.getString(R.string.settings_cache_clear));capture("clear-confirm")
        click(context.getString(R.string.settings_cancel))
        click(context.getString(R.string.settings_cache_clear));click(context.getString(R.string.settings_confirm));await("clear"){!settings.busy}
        reveal(context.getString(R.string.settings_diagnostics_export));capture("diagnostics")
        click(context.getString(R.string.settings_diagnostics_export))
        await("document picker"){root()?.packageName?.toString()?.contains("documentsui")==true}
        capture("document-picker")
        // Cancelling the system picker must not create any application export work.
        check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        await("picker cancellation") {find(context.getString(R.string.settings_diagnostics_export))!=null}
        check(!settings.exporting)
        click(context.getString(R.string.settings_diagnostics_export))
        await("second document picker"){root()?.packageName?.toString()?.contains("documentsui")==true}
        fun anyNode(node:AccessibilityNodeInfo?,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            if(node==null)return null
            if(predicate(node))return node
            for(i in 0 until node.childCount)anyNode(node.getChild(i),predicate)?.let{return it}
            return null
        }
        val drawer=anyNode(root()) {it.contentDescription?.toString() in listOf("Show roots","显示根目录","显示导航栏","Show navigation drawer")}
        check(drawer!=null && drawer.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {"Cannot open SAF roots"}
        test.waitForIdleSync();Thread.sleep(200)
        click("Theia Audit Documents")
        val save=anyNode(root()) {it.text?.toString() in listOf("Save","SAVE","保存") && it.isClickable}
        check(save!=null && save.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {"Cannot save SAF document"}
        await("diagnostic save"){settings.message==context.getString(R.string.settings_export_saved) && !settings.busy}
        val uri=DocumentsContract.buildDocumentUri(DiagnosticsFixtureProvider.AUTHORITY,"report")
        val bytes=context.contentResolver.openInputStream(uri)!!.use {it.readBytes()}
        var report=""
        ZipInputStream(ByteArrayInputStream(bytes)).use {zip->while(true){val entry=zip.nextEntry?:break;if(entry.name=="report.json")report=zip.readBytes().toString(Charsets.UTF_8)}}
        check(JSONObject(report).getInt("format")==1 && !report.contains("content://"))
        File(output,"r05-saf-diagnostics.zip").writeBytes(bytes)
        test.context.contentResolver.call(uri,"audit-mode","fail",null)
        try {
            main {settings.export(uri,com.spektralab.android.data.diagnostics.DiagnosticReport.Workspace())}
            await("destination failure"){!settings.busy}
            check(settings.message==context.getString(R.string.settings_export_failed))
        } finally {test.context.contentResolver.call(uri,"audit-mode","",null)}
        main { settings.dismissMessage() }
        test.waitForIdleSync();Thread.sleep(250)
        test.context.contentResolver.call(uri,"audit-mode","slow",null)
        try {
            // A deleted document revoked its URI grant. Obtain a new grant from the real picker.
            click(context.getString(R.string.settings_diagnostics_export))
            await("slow document picker"){root()?.packageName?.toString()?.contains("documentsui")==true}
            val navigation=anyNode(root()) {it.contentDescription?.toString() in listOf("Show roots","显示根目录","显示导航栏","Show navigation drawer")}
            check(navigation!=null && navigation.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            test.waitForIdleSync();Thread.sleep(200)
            click("Theia Audit Documents")
            val submit=anyNode(root()) {it.text?.toString() in listOf("Save","SAVE","保存") && it.isClickable}
            check(submit!=null && submit.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("diagnostic starts"){settings.exporting}
            reveal(context.getString(R.string.settings_diagnostics_cancel))
            capture("diagnostic-working");click(context.getString(R.string.settings_diagnostics_cancel))
            await("cancel destination"){!settings.busy}
            check(settings.message==context.getString(R.string.settings_cancelled))
            val status=checkNotNull(test.context.contentResolver.call(uri,"audit-status",null,null))
            check(!status.getBoolean("exists") && status.getLong("bytes")==0L) {"Cancelled diagnostic retained a complete document"}
        } finally {test.context.contentResolver.call(uri,"audit-mode","",null)}
        click(context.getString(R.string.settings_restore));capture("restore-confirm")
        click(context.getString(R.string.settings_cancel))
        settingsFile.writeText("{\"version\":999}")
        main {settings.refresh()};await("future settings"){!settings.busy};check(settings.unavailable)
        fun top(node:AccessibilityNodeInfo?):Boolean {
            if(node==null)return false
            if(node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return true
            for(i in 0 until node.childCount)if(top(node.getChild(i)))return true
            return false
        }
        repeat(6){top(root());Thread.sleep(70)}
        reveal(context.getString(R.string.settings_unavailable))
        capture("unavailable")
        check(settingsFile.readText()=="{\"version\":999}")
        test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await("home"){find("更多操作")!=null}
        return "Actual Home/settings navigation, preview choice persistence, cache confirmation/cancel/clear, real SAF provider save and ZIP readback/destination failure, restore cancel, future-file read-only state and System Back passed"
    }
}
