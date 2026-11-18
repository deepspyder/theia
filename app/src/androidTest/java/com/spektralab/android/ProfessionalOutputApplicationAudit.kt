package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.color.OutputColorSpaces
import com.spektralab.android.core.geometry.FinishedCrop
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Real shared UI -> frozen job -> native/cache -> grade/crop/size -> colour -> publisher path. */
internal class ProfessionalOutputApplicationAudit(private val test:Instrumentation,private val output:File) {
    private val context=test.targetContext
    private fun main(action:()->Unit)=test.runOnMainSync(action)
    private fun await(label:String,ready:()->Boolean) {
        val start=SystemClock.elapsedRealtime()
        while(!ready()) {check(SystemClock.elapsedRealtime()-start<180000){"R08 timeout: $label"};Thread.sleep(40)}
        test.waitForIdleSync()
    }
    fun run(raw:Boolean, heif:Boolean=false):String {
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel;main {vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        val directory=File(output,if(heif)"r08-heif" else if(raw)"r08-raw" else "r08-application").apply{mkdirs()}
        val manifest=JSONArray()
        try {
            await("engine"){vm.engine!=null && !vm.busy && !vm.heifChecking}
            val files=if(raw)listOf(File(output,"pc-parity-DSC_5099.NEF")) else listOf("png","jpg","webp","dng").map {ext ->
                val file=File(directory,"input.$ext")
                if(ext=="dng")test.context.assets.open("audit.dng").use {i->file.outputStream().use {i.copyTo(it)}} else {
                    val bitmap=Bitmap.createBitmap(601,403,Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(IntArray(601*403){i->0xff000000.toInt() or ((i%601*230/601+10)shl 16) or ((i/601*210/403+20)shl 8) or ((i*17)and 255)},0,601,0,0,601,403)
                    try{file.outputStream().use {bitmap.compress(when(ext){"jpg"->Bitmap.CompressFormat.JPEG;"webp"->Bitmap.CompressFormat.WEBP_LOSSLESS;else->Bitmap.CompressFormat.PNG},95,it)}}finally{bitmap.recycle()}
                    if(ext=="jpg")androidx.exifinterface.media.ExifInterface(file).apply {setAttribute("Orientation","6");setAttribute("Make","TheiaAudit");saveAttributes()}
                };file
            }
            for(file in files) {
                check(file.isFile)
                val uri=Uri.fromFile(file)
                val edit=EditorParams().apply {
                    grainActive=false;glareActive=false;halationActive=false
                    if(!raw) {brightness=3f;vignetteAmount=17f;finishedCrop=FinishedCrop(.07f,.11f,.83f,.77f);quarterTurns=1
                        if(file.extension=="webp"){overscanActive=true;overscanFormat="135_half";dateImprintActive=true;dateImprintText="26 11 19"}}
                }
                EditStore.save(context,uri,edit)
                main {vm.smartAutoEnabled=false;vm.dismissError();vm.openPhotos(listOf(uri))}
                await("source"){vm.errorMessage!=null || vm.canPickNeutral && !vm.busy};check(vm.errorMessage==null){vm.errorMessage.orEmpty()}
                val custom=if(raw)null else ExportSize(ExportSize.Kind.LONG_EDGE,317.0)
                if(heif)check(vm.heifAvailable){"Actual HEIF codec is unavailable on this test device"}
                val cs=if(heif)listOf("sRGB")else if(raw)listOf("sRGB","Adobe RGB (1998)")else OutputColorSpaces.names
                for(name in cs) {
                    val c=OutputColorSpaces.names.indexOf(name)
                    val formats=if(heif)listOf("HEIF")else if(raw)listOf("TIFF","PNG","JPEG")else listOf("JPEG","PNG","TIFF")
                    for(format in formats) {
                        val prefix="${file.extension}-c$c-${format.lowercase()}"
                        val options=ExportOptions(format,0,name,95,true,false,true,custom)
                        main {vm.engine!!.park()}
                        val reference=File(directory,"$prefix-reference.tiff")
                        EngineClient.create(context).use {engine ->
                            ExportSourceDecoder.open(context,engine,uri,file.extension.lowercase() in listOf("nef","dng"),edit,options){}
                            engine.renderExportOnce().use {base ->
                                val finished=FinishedRgba16Source.wrap(base,EditorGrade.from(edit),edit.finishedCrop){}
                                val sized=SizedRgba16Source.wrap(finished,custom){}
                                reference.outputStream().use {TiffWriter.writeNative(sized,if(sized.gradeApplied)EditorGrade.identity else EditorGrade.from(edit),null,it,{})}
                            }
                        }
                        val start=SystemClock.elapsedRealtime()
                        main {vm.dismissError();vm.exportFormat=format;vm.exportColorSpace=name;vm.exportCustomSize=custom;vm.exportRes=0
                            vm.exportKeepCapture=true;vm.exportKeepLocation=false;vm.exportTiffDeflate=false;vm.export();check(vm.isExporting)
                            vm.exportColorSpace="ProPhoto RGB";vm.exportTiffDeflate=true}
                        await("$prefix"){!vm.isExporting && !vm.busy};check(vm.errorMessage==null){"$prefix: ${vm.errorMessage}"}
                        val saved=Uri.parse(vm.log.substringAfterLast("相册: ").lineSequence().first().trim());check(saved.scheme=="content"){vm.log}
                        val target=File(directory,"$prefix.${if(format=="HEIF")"heic" else if(format=="JPEG")"jpg" else format.lowercase()}")
                        try {context.contentResolver.openInputStream(saved)!!.use {i->target.outputStream().use {i.copyTo(it)}}}finally{context.contentResolver.delete(saved,null,null)}
                        if(heif) {
                            val bitmap=android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(target)){decoder,_,_->decoder.allocator=android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE}
                            try {File(directory,target.name+".decoded.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
                            val exif=androidx.exifinterface.media.ExifInterface(target)
                            check(exif.getAttribute("Make")?.contains("NIKON",ignoreCase=true)==true && exif.getAttributeInt("Orientation",1)==1)
                        }
                        manifest.put(JSONObject().put("file",target.name).put("reference",reference.name).put("from",options.renderColorSpace)
                            .put("to",name).put("format",format).put("ms",SystemClock.elapsedRealtime()-start).put("bytes",target.length()))
                    }
                }
                if(raw && !heif) {
                    val start=SystemClock.elapsedRealtime()
                    main {vm.exportFormat="TIFF";vm.exportColorSpace="sRGB";vm.exportTiffDeflate=true;vm.exportCustomSize=null;vm.exportRes=0;vm.exportKeepCapture=true;vm.export()}
                    await("full lossless"){!vm.isExporting};check(vm.errorMessage==null){vm.errorMessage.orEmpty()}
                    val saved=Uri.parse(vm.log.substringAfterLast("相册: ").lineSequence().first().trim())
                    val target=File(directory,"nef-deflate.tiff")
                    try{context.contentResolver.openInputStream(saved)!!.use {i->target.outputStream().use {i.copyTo(it)}}}finally{context.contentResolver.delete(saved,null,null)}
                    manifest.put(JSONObject().put("file",target.name).put("reference","NEF-c0-tiff-reference.tiff").put("from","sRGB").put("to","sRGB").put("format","TIFF").put("ms",SystemClock.elapsedRealtime()-start).put("bytes",target.length()))
                }
            }
            File(directory,"manifest.json").writeText(manifest.toString(2))
            return "R08 shared application path: ${manifest.length()} files; original NEF=$raw; frozen colour/compression, RAW/bitmap, crops/date/EXIF/custom sizes and real profiles retained for independent complete-raster audit"
        }finally{main{vm.exportTiffDeflate=false;vm.exportColorSpace="sRGB";vm.exportCustomSize=null;activity.finish()}}
    }
}
