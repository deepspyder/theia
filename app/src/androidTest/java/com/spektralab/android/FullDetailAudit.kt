package com.spektralab.android

import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.util.Half
import com.spektralab.android.core.color.OutputColorSpaces
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.data.preview.FullDetailSession
import com.spektralab.android.engine.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

internal class FullDetailAudit(private val test:Instrumentation,private val output:File) {
    private fun source(w:Int,h:Int)=object:Rgba16Source {
        override val width=w;override val height=h
        override fun readRows(y:Int,rows:Int,destination:ShortArray) {
            check(rows in 1..32)
            for(row in 0 until rows)for(x in 0 until w)for(c in 0..3)
                destination[(row*w+x)*4+c]=value(x,y+row,c)
        }
    }
    private fun value(x:Int,y:Int,c:Int)=if(c==3)65535.toShort()else ((x*19357L+y*43331L+c*701)%65536).toShort()
    private fun half(bitmap:Bitmap):ShortArray {
        check(bitmap.config==Bitmap.Config.RGBA_F16)
        val bytes=ByteBuffer.allocate(bitmap.allocationByteCount).order(ByteOrder.nativeOrder());bitmap.copyPixelsToBuffer(bytes)
        bytes.rewind();return ShortArray(bitmap.width*bitmap.height*4).also{bytes.asShortBuffer().get(it)}
    }
    fun core():String {
        var coordinates=0
        for((w,h)in listOf(1 to 1,1025 to 769,13 to 65535,8192 to 6144))for(scale in listOf(1f,2f,4f,8f))
            for(cx in listOf(-1f,.1f,.5f,.97f,2f)) {
                val view=DetailViewport(scale,cx,1f-cx).bounded(w,h,731,417)
                val tiles=view.tiles(w,h,731,417);check(tiles.distinct().size==tiles.size)
                for(tile in tiles)check(tile.x>=0 && tile.y>=0 && tile.x+tile.width<=w && tile.y+tile.height<=h)
                for(y in 0 until 417 step 13)for(x in 0 until 731 step 17) {
                    val sx=view.centerX*w+(x-731/2f)/scale;val sy=view.centerY*h+(y-417/2f)/scale
                    if(sx>=0 && sy>=0 && sx<w && sy<h)check(tiles.count{sx>=it.x && sx<it.x+it.width && sy>=it.y && sy<it.y+it.height}==1)
                    coordinates++
                }
            }
        val anchor=DetailViewport(2f,.5f,.5f);val zoom=anchor.gesture(6001,4003,801,601,0f,0f,1.5f,311f,203f)
        check(abs((anchor.centerX*6001+(311-400.5f)/2)-(zoom.centerX*6001+(311-400.5f)/3))<.001f)
        check(abs((anchor.centerY*4003+(203-300.5f)/2)-(zoom.centerY*4003+(203-300.5f)/3))<.001f)
        val identity=EditorGrade.identity
        val grade=EditorGrade.from(EditorParams().apply{brightness=8f;vignetteAmount=43f})
        var samples=0L
        for(cs in OutputColorSpaces.names)for(g in listOf(identity,grade)) {
            val input=source(1025,769)
            val full=ByteArrayOutputStream().also{TiffWriter.writeNative(input,g,null,it,{})}.toByteArray()
            val b=ByteBuffer.wrap(full).order(ByteOrder.LITTLE_ENDIAN);val n=b.getShort(8).toInt() and 65535
            val offset=(0 until n).map{10+it*12}.first{b.getShort(it).toInt() and 65535==273}.let{b.getInt(it+8)}
            val sourceBytes=full.copyOfRange(offset,full.size)
            FullDetailSession.create(test.targetContext,input,g,cs,"test",{}).use {session->
                for(display in listOf("sRGB","Display P3"))for(view in listOf(DetailViewport(),DetailViewport(2f,.03f,.94f),DetailViewport(4f,.91f,.08f))) {
                    val a=session.visible(view,607,433,display){}
                    val again=session.visible(view,607,433,display){}
                    check(a.keys==again.keys && a.all{(k,v)->again[k]===v})
                    check(session.residentBytes<=session.budget)
                    val connector=if(cs==display)null else android.graphics.ColorSpace.connect(OutputColorSpaces.named(cs),OutputColorSpaces.named(display))
                    for((tile,bitmap)in a) {
                        val actual=half(bitmap);val rgb=FloatArray(3)
                        for(y in 0 until tile.height)for(x in 0 until tile.width) {
                            val at=((tile.y+y)*1025+tile.x+x)*6
                            for(c in 0..2)rgb[c]=((sourceBytes[at+c*2].toInt() and 255) or ((sourceBytes[at+c*2+1].toInt() and 255)shl 8))/65535f
                            connector?.transform(rgb)
                            for(c in 0..2)check(actual[(y*tile.width+x)*4+c]==Half.toHalf(rgb[c])){"Full detail tile changed a final pixel"}
                            samples+=3
                        }
                    }
                }
                val preview=session.preview(317,"sRGB"){}
                try{check(preview.width==317 && preview.height==238)}finally{preview.recycle()}
            }
        }
        val before=File("/proc/self/fd").list()!!.size
        FullDetailSession.create(test.targetContext,source(31,19),identity,"sRGB","8bit",{},quantize8Bit=true).use {session->
            val tile=session.visible(DetailViewport(),100,100,"sRGB"){}.values.single();val values=half(tile)
            for(y in 0 until 19)for(x in 0 until 31)for(c in 0..2) {
                val expected=(((value(x,y,c).toInt() and 65535)*255+32767)/65535)/255f
                check(values[(y*31+x)*4+c]==Half.toHalf(expected))
            }
        }
        repeat(10) {
            check(runCatching{FullDetailSession.create(test.targetContext,source(31,19),identity,"sRGB","cancel",{error("cancel-detail")})}.exceptionOrNull()?.message=="cancel-detail")
        }
        check(File("/proc/self/fd").list()!!.size<=before+2)
        val noSpace=object:ContextWrapper(test.targetContext){override fun getCacheDir()=object:File(test.targetContext.cacheDir.path){override fun getUsableSpace()=0L}}
        check(runCatching{FullDetailSession.create(noSpace,source(31,19),identity,"sRGB","space",{})}.isFailure)
        return "R09: $coordinates independent viewport coverage coordinates; $samples final TIFF16->F16 channel samples exact, four source/two display spaces, grade/vignette, tile reuse/budget, row bounds, cancel/no-space/FD cleanup"
    }
    fun large():String {
        val times=org.json.JSONArray()
        for((w,h)in listOf(8192 to 6144,65535 to 9)) {
            val start=android.os.SystemClock.elapsedRealtime()
            val session=FullDetailSession.create(test.targetContext,source(w,h),EditorGrade.identity,"sRGB","large",{})
            session.use {
                for(view in listOf(DetailViewport(),DetailViewport(1f,0f,0f),DetailViewport(4f,1f,1f))) {
                    val tiles=session.visible(view,1080,1741,"sRGB"){}
                    for((tile,bitmap)in tiles) {
                        val actual=half(bitmap)
                        for(y in 0 until tile.height step 7)for(x in 0 until tile.width step 11)for(c in 0..2)
                            check(actual[(y*tile.width+x)*4+c]==Half.toHalf((value(tile.x+x,tile.y+y,c).toInt() and 65535)/65535f))
                    }
                    check(session.residentBytes<=64L*1024*1024)
                }
            }
            check(runCatching{session.visible(DetailViewport(),1080,1741,"sRGB") {}}.isFailure)
            times.put(org.json.JSONObject().put("width",w).put("height",h).put("ms",android.os.SystemClock.elapsedRealtime()-start))
        }
        File(output,"r09-large.json").writeText(times.toString(2))
        return "50MP and65535px ultra-wide immutable fullU16 storage, original coordinate tiles and64MiB retained tile budget passed; closed session refuses reads"
    }
}
