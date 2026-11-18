package com.spektralab.android

import android.app.Instrumentation
import com.spektralab.android.core.geometry.FinishedCrop
import com.spektralab.android.core.geometry.Perspective
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.FinishedRgba16Source
import com.spektralab.android.data.export.SizedRgba16Source
import com.spektralab.android.engine.*
import org.json.JSONObject
import kotlin.math.*

/** Independent camera-ray reference and full-frame grade/cut reference, not matrix round trips. */
internal class GeometryMathAudit(private val test: Instrumentation) {
    private fun ray(w: Int, h: Int, vertical: Float, horizontal: Float, x: Double, y: Double): Pair<Double, Double> {
        val a = Math.toRadians(vertical.toDouble()); val b = Math.toRadians(horizontal.toDouble())
        val f = max(w, h).toDouble()
        // Forward rotated optical axis determines the translation which keeps the centre fixed.
        val centerX = f * sin(b) / (cos(a) * cos(b))
        val centerY = -f * tan(a)
        val qx = (x - w / 2.0 + centerX) / f
        val qy = (y - h / 2.0 + centerY) / f
        // Inverse rotations: Rx(-a), then Ry(-b), followed by the pinhole divide.
        val yy = cos(a) * qy + sin(a)
        val zz = -sin(a) * qy + cos(a)
        val xx = cos(b) * qx - sin(b) * zz
        val z = sin(b) * qx + cos(b) * zz
        return f * xx / z + w / 2.0 to f * yy / z + h / 2.0
    }

    fun run(): String {
        var mappings = 0
        for ((w, h) in listOf(601 to 403, 403 to 601, 6064 to 4040, 1200 to 137, 1 to 719))
            for (v in listOf(-30f, -11f, 0f, 23f, 30f)) for (a in listOf(-30f, 0f, 17f, 30f)) {
                val map = Perspective.make(w, h, v, a)
                fun inside(scale: Double) = listOf(0.0 to 0.0, w.toDouble() to 0.0, 0.0 to h.toDouble(), w.toDouble() to h.toDouble()).all { (x,y) ->
                    val p = ray(w, h, v, a, w/2.0 + (x-w/2.0)*scale, h/2.0 + (y-h/2.0)*scale)
                    p.first in -1e-6..w+1e-6 && p.second in -1e-6..h+1e-6
                }
                var lo = 0.0; var hi = 1.0
                repeat(60) { val mid = (lo+hi)/2; if (inside(mid)) lo=mid else hi=mid }
                check(abs(map.fill - lo) < 1e-8) { "fill $w/$h/$v/$a ${map.fill} vs $lo" }
                for (x in listOf(0.0, w*.17, w*.5, w.toDouble())) for (y in listOf(0.0, h*.3, h.toDouble())) {
                    val actual = map.source(x,y)
                    val expected = ray(w,h,v,a,w/2.0+(x-w/2.0)*lo,h/2.0+(y-h/2.0)*lo)
                    check(abs(actual.first-expected.first)<1e-5 && abs(actual.second-expected.second)<1e-5)
                    check(actual.first in -1e-5..w+1e-5 && actual.second in -1e-5..h+1e-5)
                    mappings++
                }
            }
        check(Perspective.make(17,29,0f,0f).inverse.contentEquals(doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)))
        val defaults = EditorParams()
        val legacy = defaults.toJson()
        for (key in listOf("film_off","perspective_vertical","perspective_horizontal","finished_crop")) check(!legacy.has(key))
        val edited = defaults.copy().apply { filmOff=true; perspectiveVertical=-13f; perspectiveHorizontal=22f; finishedCrop=FinishedCrop(.13f,.07f,.66f,.81f) }
        val restored = EditorParams.fromJson(edited.toJson())
        check(restored.toJson().toString()==edited.toJson().toString())
        val (films,papers) = StockCatalog.load(test.targetContext)
        val template = EditTemplate.capture(edited, setOf(EditGroup.FILM,EditGroup.GEOMETRY))
        val transferred = checkNotNull(template.applyTo(defaults, true, films,papers).params)
        check(transferred.filmOff && transferred.finishedCrop==edited.finishedCrop && transferred.perspectiveHorizontal==22f)
        val old = EditTemplate.capture(defaults, setOf(EditGroup.FILM,EditGroup.GEOMETRY))
        val reset = checkNotNull(old.applyTo(edited,true,films,papers).params)
        check(!reset.filmOff && reset.finishedCrop.isFull && reset.perspectiveVertical==0f)
        val target = defaults.copy().apply { perspectiveVertical=7f;finishedCrop=FinishedCrop(.1f,.1f,.8f,.8f) }
        target.copySettingsFrom(edited)
        check(target.perspectiveVertical==7f && target.finishedCrop.width==.8f && target.filmOff)
        check(EditSnapshot.capture(EditorParams.fromJson(JSONObject(legacy.toString()).put("film_off",false).put("perspective_vertical",0)))==EditSnapshot.capture(EditorParams.fromJson(legacy)))
        for ((key,value) in listOf("film_off" to "true","perspective_vertical" to 31,"perspective_horizontal" to true,
            "finished_crop" to JSONObject().put("version",2))) {
            check(runCatching { EditorParams.fromJson(JSONObject(legacy.toString()).put(key,value)) }.isFailure)
        }
        val wire = JSONObject(edited.apply { overscanActive=true; dateImprintActive=true }.openJson(601,403))
        check(!wire.getBoolean("overscan_active") && !wire.getBoolean("date_imprint_active"))
        check(edited.overscanActive && edited.dateImprintActive)
        var cutPixels = 0
        for ((w,h) in listOf(1 to 1, 3 to 7, 113 to 79, 81 to 121)) {
            val data = ShortArray(w*h*4) { i -> if(i%4==3) (-1).toShort() else ((i*931+123)%65536).toShort() }
            val source = object : Rgba16Source {
                override val width=w;override val height=h
                override fun readRows(y:Int,rows:Int,destination:ShortArray) { data.copyInto(destination,0,y*w*4,(y+rows)*w*4) }
            }
            for (gradeParams in listOf(EditorParams(),EditorParams().apply { vignetteAmount=83f;vignetteSize=29f;vignetteFeather=71f;contrast=13f;gradeExposure=.3f },
                EditorParams().apply { toneCurves=com.spektralab.android.core.color.ToneCurves.IDENTITY.with(com.spektralab.android.core.color.CurveChannel.RGB, com.spektralab.android.core.color.ToneCurve.of(listOf(com.spektralab.android.core.color.CurvePoint(0f,0f),com.spektralab.android.core.color.CurvePoint(.4f,.51f),com.spektralab.android.core.color.CurvePoint(1f,1f))));vignetteAmount=50f })) {
                val grade=EditorGrade.from(gradeParams)
                for(crop in listOf(FinishedCrop.FULL,FinishedCrop(.13f,.07f,.66f,.81f),FinishedCrop(.97f,.97f,.03f,.03f))) {
                    val finished=FinishedRgba16Source.wrap(source,grade,crop) {}
                    if(crop.isFull) { check(finished===source);continue }
                    val cut=crop.pixels(w,h)
                    val got=ShortArray(finished.width*finished.height*4)
                    finished.readRows(0,finished.height,got)
                    val pixel=FloatArray(3)
                    for(y in 0 until cut.height) for(x in 0 until cut.width) {
                        val i=((cut.top+y)*w+cut.left+x)*4
                        grade.gradeAt(data[i],data[i+1],data[i+2],pixel,cut.left+x,cut.top+y,w,h)
                        for(c in 0..2) {
                            val expected=if(grade.isIdentity)data[i+c].toInt() and 65535 else (pixel[c]*65535f).roundToInt().coerceIn(0,65535)
                            check(abs((got[(y*cut.width+x)*4+c].toInt() and 65535)-expected)<=1)
                        }
                        cutPixels++
                    }
                    for (space in listOf("sRGB", "Display P3")) {
                        val frame = SpkResult(data,w,h,w,0.0,false,false,"")
                        val full = ColorPipeline.applyGradeToBitmap(data,w,h,grade,space).first
                        val preview = FinishedPreview.render(frame,grade,crop,space) {}
                        try {
                            val histogram = Histogram(IntArray(256),IntArray(256),IntArray(256))
                            for(y in 0 until cut.height)for(x in 0 until cut.width) {
                                val expected=full.getPixel(cut.left+x,cut.top+y)
                                check(preview.first.getPixel(x,y)==expected)
                                histogram.r[(expected ushr 16)and 255]++;histogram.g[(expected ushr 8)and 255]++;histogram.b[expected and 255]++
                            }
                            check(preview.second.r.contentEquals(histogram.r) && preview.second.g.contentEquals(histogram.g) && preview.second.b.contentEquals(histogram.b))
                        } finally {full.recycle();preview.first.recycle()}
                    }
                    val sized=SizedRgba16Source.wrap(finished,ExportSize(ExportSize.Kind.LONG_EDGE,11.0)) {}
                    check(sized.gradeApplied)
                    var checks=0
                    val cancelled=FinishedRgba16Source.wrap(source,grade,crop) { if(++checks==1) error("cancel") }
                    check(runCatching { cancelled.readRows(0,1,ShortArray(cut.width*4)) }.exceptionOrNull()?.message=="cancel")
                }
            }
        }
        return "$mappings independent pinhole mappings; $cutPixels full-canvas graded crop pixels; legacy/default/template/validation/cancel contracts"
    }
}
