package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.geometry.FinishedCrop
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File

/** Full-canvas independently cut references through actual virtual-copy batch and light-table output. */
internal class GeometryCompositionAudit(private val test: Instrumentation,private val output:File) {
    private val context=test.targetContext
    private fun await(condition:()->Boolean){val t=SystemClock.elapsedRealtime();while(!condition()){check(SystemClock.elapsedRealtime()-t<150000);Thread.sleep(40)}}
    private fun same(a:Bitmap,b:Bitmap){check(a.width==b.width && a.height==b.height);val aa=IntArray(a.width);val bb=IntArray(b.width)
        var delta=0;for(y in 0 until a.height){a.getPixels(aa,0,a.width,0,y,a.width,1);b.getPixels(bb,0,b.width,0,y,b.width,1)
            for(x in aa.indices)for(s in listOf(0,8,16))delta=maxOf(delta,kotlin.math.abs(((aa[x] ushr s)and 255)-((bb[x] ushr s)and 255)))}
        if(delta>2) {
            File(output,"geometry-composition-actual.png").outputStream().use {a.compress(Bitmap.CompressFormat.PNG,100,it)}
            File(output,"geometry-composition-reference.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        check(delta<=2){"Composition pixel mismatch $delta"}}
    fun run(sceneCurve: Boolean = false):String {
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm:EditorViewModel;test.runOnMainSync{vm=ViewModelProvider(activity)[EditorViewModel::class.java]}
        await{vm.engine!=null && !vm.busy && vm.films.isNotEmpty()};val auto=vm.smartAutoEnabled;test.runOnMainSync{vm.smartAutoEnabled=false}
        val media=MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun ids()=context.contentResolver.query(media,arrayOf("_id"),null,null,null)!!.use {c->buildSet{while(c.moveToNext())add(c.getLong(0))}}
        val initial=ids();var variant:EditVersion?=null
        val references=mutableListOf<Bitmap>()
        try {
            val file=File(output,"geometry-composition-${System.nanoTime()}.png")
            val source=Bitmap.createBitmap(601,403,Bitmap.Config.ARGB_8888)
            source.setPixels(IntArray(601*403){i->0xff000000.toInt() or ((i%601*170/601+30)shl 16) or ((i/601*160/403+40)shl 8) or 119},0,601,0,0,601,403)
            try{file.outputStream().use{source.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{source.recycle()}
            val uri=Uri.fromFile(file)
            val params=EditorParams().apply{filmOff=true;grainActive=false;glareActive=false;halationActive=false;perspectiveVertical=13f;perspectiveHorizontal=-7f
                finishedCrop=FinishedCrop(.07f,0f,.8f,1f);vignetteAmount=55f;quarterTurns=1;flipV=true}
            if (sceneCurve) { params.filmOff = false; params.sceneCurves = SceneCurveAudit.shaped() }
            EditStore.save(context,uri,params)
            val asset=EditStore.savedPhotos(context).first{it.uri==uri.toString()};val default=EditStore.editVersions(context,asset.assetId).first()
            val copied=EditStore.createEditVersion(context,default,"Geometry ${System.nanoTime()}");variant=copied
            val other=params.copy().apply {filmOff=false;overscanActive=true;dateImprintActive=true;dateImprintText="26 11 18";finishedCrop=FinishedCrop(.11f,0f,.72f,1f)}
            if (sceneCurve) other.sceneCurves = com.spektralab.android.core.color.SceneCurves(
                com.spektralab.android.core.color.ToneCurves.IDENTITY.with(com.spektralab.android.core.color.CurveChannel.RED,
                    com.spektralab.android.core.color.ToneCurve.IDENTITY.add(.5f, .57f)))
            EditStore.save(context,copied.editUri,other)
            val uris=listOf(uri,copied.editUri);val edits=listOf(params,other)
            EngineClient.create(context).use {engine->for(p in edits){
                val bitmap=ExportSourceDecoder.bitmap(context,uri,0)
                try{engine.openBitmap(bitmap,p.openJson(bitmap.width,bitmap.height))}finally{bitmap.recycle()}
                engine.renderExportOnce().use{base->
                    val rgba=ShortArray(base.width*base.height*4);base.readRows(0,base.height,rgba)
                    val full=ColorPipeline.applyGradeToBitmap(rgba,base.width,base.height,EditorGrade.from(p)).first
                    val crop=p.finishedCrop.pixels(base.width,base.height)
                    val cut=Bitmap.createBitmap(full,crop.left,crop.top,crop.width,crop.height)
                    references+=cut;if(cut!==full)full.recycle()
                }
            }}
            test.runOnMainSync{vm.openPhotos(uris)};await{vm.errorMessage!=null || vm.canPickNeutral && !vm.busy};check(vm.errorMessage==null)
            val seen=ids();test.runOnMainSync{vm.exportFormat="PNG";vm.exportRes=0;vm.exportCustomSize=null;vm.batchExport()};await{!vm.isBatchExporting};check(vm.errorMessage==null){vm.errorMessage.orEmpty()}
            val added=(ids()-seen).sorted();check(added.size==2)
            for((i,id)in added.withIndex()){val b=context.contentResolver.openInputStream(ContentUris.withAppendedId(media,id)).use{BitmapFactory.decodeStream(it)}!!;try{same(b,references[i])}finally{b.recycle()}}
            for((i,editUri)in uris.withIndex()){
                val expected=references[i]
                val tableSource=EditStore.tableSource(context,TableSource(editUri.toString(),"Geometry $i",pixelWidth=expected.width,pixelHeight=expected.height,appearance=TableAppearance.EDITED))
                val scene=LightTableScene((0 until 9).map { at -> TablePlacement(java.util.UUID.randomUUID().toString(),tableSource,
                    (at%3)*expected.width.toFloat(),(at/3)*expected.height.toFloat(),expected.width.toFloat(),expected.height.toFloat()) })
                val request=TableExportRequest(scene,TableExportOptions(mode=TableOutputMode.CONTACT,longEdge=1600),null)
                val before=ids();var outcome:Result<String>?=null
                test.runOnMainSync{check(vm.exportTableProject(request){outcome=it})};await{!vm.isExporting && outcome!=null};check(outcome!!.isSuccess){outcome!!.exceptionOrNull().toString()}
                val b=context.contentResolver.openInputStream(ContentUris.withAppendedId(media,(ids()-before).single())).use{BitmapFactory.decodeStream(it)}!!
                val font=ResourcesCompat.getFont(context,R.font.resource_han_rounded_regular)!!
                val edge=TableExportSession.prepare(context,request,{}, {_,_->}).use {it.inputs.single().requestedEdge}
                val long=maxOf(expected.width,expected.height)
                val staged=if(long>edge)Bitmap.createScaledBitmap(expected,(expected.width.toLong()*edge/long).toInt(),(expected.height.toLong()*edge/long).toInt(),true)else expected
                val expectedTable=TableComposition.render(request.plan(),font){staged}
                try{same(b,expectedTable)}finally{b.recycle();expectedTable.recycle();if(staged!==expected)staged.recycle()}
                check(EditSnapshot.capture(EditStore.load(context,editUri)!!)==EditSnapshot.capture(edits[i]))
            }
            return (if (sceneCurve) "Independent per-version Decode curves; " else "") + "Real virtual-copy batch and two 1600px light-table exports match independently graded-then-cut references; digital + film/border/date/rotation/perspective; source edits unchanged"
        }finally {references.forEach(Bitmap::recycle);variant?.let{EditStore.deleteEditVersion(context,EditStore.editVersion(context,it.id))};(ids()-initial).forEach{context.contentResolver.delete(ContentUris.withAppendedId(media,it),null,null)};test.runOnMainSync{vm.smartAutoEnabled=auto;activity.finish()}}
    }
}
