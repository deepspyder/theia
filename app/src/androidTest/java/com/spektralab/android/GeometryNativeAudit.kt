package com.spektralab.android

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.os.SystemClock
import com.spektralab.android.core.geometry.Perspective
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.engine.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

internal class GeometryNativeAudit(private val test: Instrumentation, private val output: File) {
    private val context = test.targetContext
    private val timings = JSONArray()
    private fun now() = SystemClock.elapsedRealtimeNanos()/1e6
    private fun encode(v: Double): Int = ((if(v<=.0031308) 12.92*v else 1.055*v.pow(1/2.4)-.055)*65535).roundToInt().coerceIn(0,65535)
    private fun hash(source: Rgba16Source): String {
        val hash=MessageDigest.getInstance("SHA-256")
        val row=ShortArray(source.width*4);val bytes=ByteBuffer.allocate(row.size*2).order(ByteOrder.LITTLE_ENDIAN)
        for(y in 0 until source.height) { source.readRows(y,1,row);bytes.clear();bytes.asShortBuffer().put(row);hash.update(bytes.array()) }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    fun run(mode: String): String {
        if(mode in setOf("geometry-editor","geometry-layout","geometry-process","geometry-raw-export","geometry-navigation")) return GeometryEditorAudit(test,output).run(mode)
        StockCatalog.load(context)
        var checked = 0
        EngineClient.create(context).use { engine ->
            val w=79;val h=53
            val input=FloatArray(w*h*3) { i -> when(i%3) { 0->(i/3%w+.5f)/w;1->(i/3/w+.5f)/h;else->.18f } }
            val p=EditorParams().apply { filmOff=true }
            for(turn in 0..3) for(flipH in listOf(false,true)) for(flipV in listOf(false,true))
                for(angles in listOf(0f to 0f, 19f to -13f)) {
                    p.quarterTurns=turn;p.flipH=flipH;p.flipV=flipV;p.perspectiveVertical=angles.first;p.perspectiveHorizontal=angles.second
                    p.cropX=.13f;p.cropY=.09f;p.cropW=.72f;p.cropH=.81f;p.rotationDeg=if(turn%2==0)0f else 7f
                    val json=p.openJson(w,h)
                    engine.open(input,w,h,3,json)
                    val managed=engine.render("full")
                    engine.renderExportOnce().use { result ->
                        check(result.width==managed.width && result.height==managed.height)
                        val actual=ShortArray(result.width*result.height*4);result.readRows(0,result.height,actual)
                        check(actual.contentEquals(managed.rgba16))
                        val map=Perspective.make(w,h,angles.first,angles.second)
                        fun sample(x:Double,y:Double,c:Int):Double {
                            val px=x.coerceIn(0.0,w-1.0);val py=y.coerceIn(0.0,h-1.0)
                            val ix=px.toInt();val iy=py.toInt();val fx=px-ix;val fy=py-iy
                            fun at(xx:Int,yy:Int)=input[(yy*w+xx)*3+c].toDouble()
                            return (at(ix,iy)*(1-fx)+at(min(ix+1,w-1),iy)*fx)*(1-fy)+(at(ix,min(iy+1,h-1))*(1-fx)+at(min(ix+1,w-1),min(iy+1,h-1))*fx)*fy
                        }
                        for(y in 0 until result.height) for(x in 0 until result.width) {
                            val u=if(flipH)1-(x+.5)/result.width else (x+.5)/result.width
                            val v=if(flipV)1-(y+.5)/result.height else (y+.5)/result.height
                            val a=when(turn){1->v;2->1-u;3->1-v;else->u}
                            val b=when(turn){1->1-u;2->1-v;3->u;else->v}
                            val angle=Math.toRadians(p.rotationDeg.toDouble())
                            val dx=(a-.5)*p.cropW*w;val dy=(b-.5)*p.cropH*h
                            val point=map.source((p.cropX+p.cropW.toDouble()/2)*w+dx*cos(angle)-dy*sin(angle),
                                (p.cropY+p.cropH.toDouble()/2)*h+dx*sin(angle)+dy*cos(angle))
                            for(c in 0..2) check(abs((actual[(y*result.width+x)*4+c].toInt() and 65535)-encode(sample(point.first-.5,point.second-.5,c)))<=1)
                            check(actual[(y*result.width+x)*4+3].toInt()==-1);checked++
                        }
                        // CPU result ownership survives closing its session and validates strip bounds.
                        check(runCatching { result.readRows(-1,1,ShortArray(result.width*4)) }.isFailure)
                    }
                }
            // Wide-gamut source conversion: independent Android colour connector at encoded ProPhoto points.
            val patches=floatArrayOf(.02f,.03f,.01f,.18f,.18f,.18f,.5f,.1f,.2f,.7f,.7f,.7f)
            for(target in listOf("sRGB","Display P3")) {
                val clean=EditorParams().apply {filmOff=true}
                engine.open(patches,4,1,3,clean.openJson(4,1,target,"ProPhoto RGB"))
                engine.renderExportOnce().use { result ->
                    val values=ShortArray(16);result.readRows(0,1,values)
                    val source=ColorSpace.get(ColorSpace.Named.PRO_PHOTO_RGB)
                    val destination=ColorSpace.get(if(target=="sRGB")ColorSpace.Named.SRGB else ColorSpace.Named.DISPLAY_P3)
                    val connector=ColorSpace.connect(source,destination)
                    val rgb=source as ColorSpace.Rgb
                    for(i in 0..3) {
                        val color=FloatArray(3) { rgb.oetf.applyAsDouble(patches[i*3+it].toDouble()).toFloat() }
                        connector.transform(color)
                        for(c in 0..2) check(abs((values[i*4+c].toInt() and 65535)-(color[c]*65535).roundToInt())<=24) { "ProPhoto $target patch=$i channel=$c" }
                    }
                }
            }
            val bitmap=Bitmap.createBitmap(31,17,Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff426587.toInt())
                val edit=EditorParams().apply {filmOff=true;overscanActive=true;dateImprintActive=true}
                engine.openBitmap(bitmap,edit.openJson(31,17));val before=engine.render("full")
                engine.setParams("{\"geometry_flip_h\":true}");check(JSONObject(engine.getParams()).getBoolean("geometry_flip_h"))
                val after=engine.render("full");check(before.rgba16.contentEquals(after.rgba16) && after.width==31 && after.height==17)
                check(runCatching { engine.solve("both") }.isFailure)
                engine.cancel();check(runCatching { engine.render("full") }.exceptionOrNull()?.message?.contains("cancel",true)==true)
                engine.setChangedParams(edit.deltaJson(31,17));check(engine.render("full").rgba16.contentEquals(before.rgba16))
                check(JSONObject(engine.progress()).getBoolean("done"))
            } finally { bitmap.recycle() }
            val film=EditorParams().apply { grainActive=false;halationActive=false;glareActive=false;autoExposure=false }
            engine.open(input,w,h,3,film.openJson(w,h));val filmBefore=engine.render("full").rgba16
            engine.open(input,w,h,3,film.copy().apply { filmOff=true }.openJson(w,h));engine.render("full")
            engine.open(input,w,h,3,film.openJson(w,h));check(filmBefore.contentEquals(engine.render("full").rgba16))
            // Pre-ingress cancellation and cancellation during CPU projection, followed by a fresh binding.
            val pool=Executors.newSingleThreadExecutor()
            try {
                val large=FloatArray(2400*1600*3) { .18f }
                val edit=EditorParams().apply { perspectiveVertical=22f }
                val opening=pool.submit<Boolean> { runCatching { engine.open(large,2400,1600,3,edit.openJson(2400,1600)) }.isFailure }
                Thread.sleep(20);engine.cancel()
                check(opening.get(30,TimeUnit.SECONDS)) { "Perspective ingress was not cancelled" }
                engine.open(input,w,h,3,film.openJson(w,h));check(engine.render("full").width==w)
            } finally { pool.shutdownNow() }
            if(mode=="geometry-native-raw") {
                val source=File(output,"pc-parity-DSC_5099.NEF");check(source.isFile)
                for(v in listOf(0f,17f)) {
                    val edit=EditorParams().apply {filmOff=true;perspectiveVertical=v}
                    val start=now()
                    RawDecoder.decodeForExport(context,Uri.fromFile(source),0).use { raw ->
                        val decoded=now();engine.openRaw(raw,edit.openJson(raw.width,raw.height,"sRGB","ProPhoto RGB"));val opened=now()
                        engine.renderExportOnce().use { result ->
                            check(result.width==6064 && result.height==4040)
                            val rendered=now();val digest=hash(result)
                            timings.put(JSONObject().put("vertical",v).put("decode_ms",decoded-start).put("open_ms",opened-decoded)
                                .put("render_ms",rendered-opened).put("read_hash_ms",now()-rendered).put("pss_kib",android.os.Debug.getPss()).put("sha256",digest))
                        }
                    }
                }
                File(output,"geometry-native-timing.json").writeText(timings.toString(2))
            }
        }
        return "$checked native digital geometry pixels, managed/native ownership, ProPhoto/P3 connector, film restoration, cancellation and rebind; timings=$timings"
    }
}
