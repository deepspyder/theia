package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.color.OutputColorSpaces
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File

/** Native real-photo controls, captured in each device configuration by the host runner. */
internal class ProfessionalOutputUiAudit(private val test:Instrumentation,private val output:File) {
    private fun root():AccessibilityNodeInfo? {test.uiAutomation.clearCache();return test.uiAutomation.rootInActiveWindow}
    private fun find(label:String,node:AccessibilityNodeInfo?=root()):AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.isVisibleToUser && (node.text?.toString()==label || node.contentDescription?.toString()==label))return node
        for(i in 0 until node.childCount)find(label,node.getChild(i))?.let{return it}
        return null
    }
    private fun await(label:String,ready:()->Boolean){val start=SystemClock.elapsedRealtime();while(!ready()){check(SystemClock.elapsedRealtime()-start<90000){"R08 UI timeout $label"};Thread.sleep(80)};test.waitForIdleSync()}
    private fun scroll(node:AccessibilityNodeInfo?,direction:Int):Boolean {
        if(node==null)return false
        if(node.isScrollable && node.performAction(direction))return true
        for(i in 0 until node.childCount)if(scroll(node.getChild(i),direction))return true
        return false
    }
    private fun reveal(label:String) {
        for(direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))repeat(20){
            if(find(label)!=null)return
            scroll(root(),direction);test.waitForIdleSync();Thread.sleep(70)
        };error("R08 missing $label")
    }
    private fun click(label:String) {
        reveal(label);var node=find(label)
        while(node!=null && !node.isClickable)node=node.parent
        check(node!=null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){"R08 cannot click $label"}
        test.waitForIdleSync();Thread.sleep(200)
    }
    private fun capture(label:String) {
        test.waitForIdleSync();Thread.sleep(250)
        val bitmap=checkNotNull(test.uiAutomation.takeScreenshot())
        try{File(output,"r08-ui-$label.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    fun run():String {
        val context=test.targetContext
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel;test.runOnMainSync{vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        val source=File(output,"r08-ui-photo.jpg")
        try {
            await("engine"){vm.engine!=null && !vm.busy && !vm.heifChecking}
            test.context.assets.open("13mb.jpg").use {i->source.outputStream().use {i.copyTo(it)}}
            val uri=Uri.fromFile(source);EditStore.save(context,uri,EditorParams().apply {grainActive=false;glareActive=false;halationActive=false})
            test.runOnMainSync{vm.smartAutoEnabled=false;vm.openPhotos(listOf(uri))}
            await("photo"){vm.errorMessage!=null || vm.canPickNeutral && !vm.busy};check(vm.errorMessage==null){vm.errorMessage.orEmpty()}
            await("home photo"){find("继续显影")!=null};click("继续显影")
            await("editor navigation"){find("导出")!=null};click("导出");click("PNG");capture("png")
            click("色彩空间");capture("spaces")
            click("Adobe RGB (1998)");check(vm.exportColorSpace=="Adobe RGB (1998)")
            click("JPEG");capture("jpeg-adobe")
            click("TIFF");click(context.getString(R.string.output_tiff_deflate));check(vm.exportTiffDeflate)
            click("色彩空间");click("ProPhoto RGB");capture("tiff-prophoto")
            click("高级设置");reveal("RGB · 16 bit");capture("advanced")
            click("高级设置")
            if(vm.heifAvailable) {
                click("HEIF");check(vm.exportColorSpace=="sRGB")
                reveal(context.getString(R.string.output_heif_sdr));capture("heif")
                val capabilitySetter=EditorViewModel::class.java.getDeclaredMethod("setHeifAvailable",Boolean::class.javaPrimitiveType).apply{isAccessible=true}
                test.runOnMainSync{capabilitySetter.invoke(vm,false)}
                reveal(context.getString(R.string.output_heif_disabled));capture("heif-unavailable")
                test.runOnMainSync{
                    vm.export();check(!vm.isExporting && vm.errorMessage==context.getString(R.string.output_heif_unavailable));vm.dismissError()
                    vm.batchExport();check(!vm.isBatchExporting && vm.errorMessage==context.getString(R.string.output_heif_unavailable));vm.dismissError()
                    capabilitySetter.invoke(vm,true)
                }
            } else {reveal(context.getString(R.string.output_heif_disabled));capture("heif-unavailable")}
            click("PNG");click("色彩空间");click("sRGB")
            check(OutputColorSpaces.names.size==4)
            reveal("导出当前照片")
            val button=android.graphics.Rect();find("导出当前照片")!!.getBoundsInScreen(button)
            val window=android.graphics.Rect();root()!!.getBoundsInScreen(window)
            check(button.bottom<=window.bottom && button.height()>0){"Pinned export outside safe viewport"}
            return "Real-photo native UI: JPEG/PNG/TIFF/HEIF capability, all four colour options, lossless TIFF toggle, correct 16-bit/SDR labels and safe export action; current viewport captures"
        }finally{test.runOnMainSync{vm.exportTiffDeflate=false;vm.exportColorSpace="sRGB";activity.finish()}}
    }
}
