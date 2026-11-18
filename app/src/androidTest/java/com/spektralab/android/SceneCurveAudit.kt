package com.spektralab.android

import android.app.Instrumentation
import com.spektralab.android.core.color.*
import com.spektralab.android.core.model.*
import com.spektralab.android.engine.*
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.*

internal class SceneCurveAudit(private val test: Instrumentation, private val output: File) {
    companion object {
        fun shaped(): SceneCurves = SceneCurves(ToneCurves.IDENTITY
            .with(CurveChannel.LUMA, ToneCurve.IDENTITY.add(.3f, .37f).add(.7f, .66f))
            .with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.52f, .56f))
            .with(CurveChannel.BLUE, ToneCurve.IDENTITY.add(.6f, .63f)))
    }

    fun models(): String {
        val base = EditorParams()
        check(!base.toJson().has("scene_curves") && base.sceneCurves.wire == "")
        val parsed = EditorParams.fromJson(base.toJson())
        check(EditSnapshot.capture(base) == EditSnapshot.capture(parsed))
        val curves = shaped()
        val source = base.copy().apply { sceneCurves = curves }
        check(source.copy().sceneCurves == curves)
        check(source.toJson().getJSONObject("scene_curves").getInt("stops") == 8)
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val german = SceneCurves(curves.curves).wire
            Locale.setDefault(Locale.US)
            check(german == SceneCurves(curves.curves).wire)
        } finally { Locale.setDefault(oldLocale) }
        val table = curves.wire.split(',').map(String::toFloat)
        check(table.size == 645 && table.all { it.isFinite() && it in 0f..1f })
        for (channel in CurveChannel.entries) for (sample in 0..128) {
            val expected = (curves.curves[channel].evaluate(sample / 128f) * 100000).roundToInt() / 100000f
            check(abs(table[channel.ordinal * 129 + sample] - expected) <= 1e-5f)
        }
        val diagonal = SceneCurves(ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f,.5f)))
        check(diagonal.isIdentity && diagonal.wire.isEmpty() && !diagonal.isDefault)
        val redundant = base.copy().apply { sceneCurves = diagonal }
        check(redundant.copy().sceneCurves == diagonal)
        check(JSONObject(source.deltaJson(17,13)).getString("scene_curve") == curves.wire)
        val future = JSONObject().put("version",99).put("keep","future scene data")
        val unreadable = EditorParams.fromJson(base.toJson().put("scene_curves",future))
        check(unreadable.unreadableSceneCurves != null && unreadable.copy().toJson().getJSONObject("scene_curves").getString("keep") == "future scene data")
        check(runCatching { unreadable.openJson(17,13) }.isFailure)
        check(JSONObject(unreadable.previewOpenJson(17,13)).getString("scene_curve").isEmpty())
        check(JSONObject(unreadable.copy().apply {filmOff=true}.openJson(17,13)).getString("scene_curve").isEmpty())
        for (bad in listOf<Any>("not curves",JSONObject(curves.toJson().toString()).put("stops",7),
            JSONObject(curves.toJson().toString()).apply {getJSONObject("curves").remove("blue")},
            JSONObject(curves.toJson().toString()).apply {getJSONObject("curves").getJSONArray("rgb").getJSONArray(0).put(0,"0")},
            JSONObject(curves.toJson().toString()).apply {getJSONObject("curves").getJSONArray("rgb").getJSONArray(0).put(1,Double.POSITIVE_INFINITY.toString())})) {
            val p=EditorParams.fromJson(base.toJson().put("scene_curves",bad))
            check(p.unreadableSceneCurves!=null && p.copy().unreadableSceneCurves==p.unreadableSceneCurves)
        }
        val (films,papers)=StockCatalog.load(test.targetContext)
        for(raw in listOf(false,true)) {
            val applied=EditTemplate.capture(source,setOf(EditGroup.DECODE)).applyTo(base,raw,films,papers)
            check(applied.params!!.sceneCurves==curves)
            check(applied.params!!.toneCurves.isDefault)
            val reset=EditTemplate.capture(base,setOf(EditGroup.DECODE)).applyTo(source,raw,films,papers)
            check(reset.params!!.sceneCurves.isDefault)
        }
        val post=base.copy().apply {toneCurves=curves.curves}
        val onlyPost=EditTemplate.capture(post,setOf(EditGroup.CURVES)).applyTo(source,false,films,papers).params!!
        check(onlyPost.sceneCurves==curves && onlyPost.toneCurves==curves.curves)
        check(runCatching { EditTemplate.capture(unreadable,setOf(EditGroup.DECODE)) }.isFailure)
        val copied=base.copy().apply {cropX=.1f;cropW=.8f};copied.copySettingsFrom(source)
        check(copied.sceneCurves==curves && copied.cropX==.1f)
        fun key(edit:EditorParams)=RenderCacheIdentity("1".repeat(64),"decode","render","2".repeat(64),17,13,"sRGB","sRGB",false,1,EditSnapshot.capture(edit)).key
        check(key(source)!=key(base) && key(source.copy())==key(source))
        return "645 stable locale-independent wire samples; neutral/diagonal, strict version/axis/type preservation, preview-only fallback/export guard, independent Decode/post groups, JPEG+RAW transfer and cache identities"
    }

    /** Reference operates on linear input before the film, never on the rendered RGB16. */
    private fun apply(input: FloatArray, curves: SceneCurves, exposure: Float, weights: DoubleArray): FloatArray {
        val table=curves.wire.split(',').map(String::toDouble)
        fun gain(value:Double,row:Int):Double {
            if(value<=0 || !value.isFinite())return 1.0
            val coordinate=((log2(value/.184f.toDouble())+exposure+8)/16).coerceIn(0.0,1.0)
            val at=coordinate*128;val low=min(at.toInt(),127);val f=at-low
            val mapped=table[row*129+low]*(1-f)+table[row*129+low+1]*f
            return 2.0.pow(16*(mapped-coordinate))
        }
        return input.copyOf().also { result ->
            for(i in input.indices step 3) {
                var rgb=DoubleArray(3){input[i+it].toDouble()}
                val y=(0..2).sumOf {weights[it].toFloat().toDouble()*max(rgb[it],0.0)}
                val luma=gain(y,1);rgb=DoubleArray(3){rgb[it]*luma}
                rgb=DoubleArray(3){rgb[it]*gain(rgb[it],0)}
                for(c in 0..2)result[i+c]=(rgb[c]*gain(rgb[c],c+2)).toFloat()
            }
        }
    }

    fun native(): String {
        StockCatalog.load(test.targetContext)
        val w=113;val h=79
        val input=FloatArray(w*h*3){i->val t=(i/3%w)/112.0
            (.184*2.0.pow(-6+9*t)*(when(i%3){0->.91;1->1.07;else->.77})).toFloat()}
        val shaped=shaped()
        var samples=0;var maximum=0
        EngineClient.create(test.targetContext).use { engine ->
            for(space in listOf("sRGB","ProPhoto RGB")) for(ev in listOf(-2f,0f,2f)) {
                val p=EditorParams().apply {autoExposure=false;grainActive=false;glareActive=false;halationActive=false;exposureEv=ev;sceneCurves=shaped}
                engine.open(input,w,h,3,p.openJson(w,h,inputColorSpace=space))
                val actual=engine.render("full")
                val weights=if(space=="sRGB")doubleArrayOf(.2126729,.7151522,.0721750)else doubleArrayOf(.2880402,.7118741,.0000857)
                val corrected=apply(input,shaped,ev,weights)
                val reference=p.copy().apply {sceneCurves=SceneCurves.IDENTITY}
                engine.open(corrected,w,h,3,reference.openJson(w,h,inputColorSpace=space))
                val expected=engine.render("full")
                check(actual.width==expected.width && actual.height==expected.height)
                var delta=0
                for(i in actual.rgba16.indices)delta=maxOf(delta,abs((actual.rgba16[i].toInt() and 65535)-(expected.rgba16[i].toInt() and 65535)))
                maximum=maxOf(maximum,delta)
                check(delta<=8){"Linear-stage oracle $space/$ev drift=$delta"};samples+=actual.width*actual.height
            }
            val p=EditorParams().apply {grainActive=false;glareActive=false;halationActive=false;autoExposure=false}
            engine.open(input,w,h,3,p.openJson(w,h));val baseline=engine.render("live")
            p.sceneCurves=shaped;engine.setChangedParams(p.deltaJson(w,h))
            val changed=engine.render("live");check(!changed.reprint && !changed.rgba16.contentEquals(baseline.rgba16))
            engine.setParams("{\"print_exposure\":1.1}")
            val printed=engine.render("live");check(printed.reprint)
            p.printExposure=1.1f
            engine.open(input,w,h,3,p.openJson(w,h))
            check(engine.render("live").rgba16.contentEquals(printed.rgba16))
            p.sceneCurves=SceneCurves.IDENTITY;p.printExposure=1f
            engine.setChangedParams(p.deltaJson(w,h));check(engine.render("live").rgba16.contentEquals(baseline.rgba16))
            check(JSONObject(engine.getParams()).getString("scene_curve").isEmpty())
            for(bad in listOf("0,1", "0,1,0,1,0,1,0,1,0,NaN", "0,1,0,1,0,1,0,1,0,2", "0,1,0,1,0,1,0,1,0,1,", "0,".repeat(5130))) {
                check(runCatching{engine.setParams(JSONObject().put("scene_curve",bad).toString())}.isFailure)
            }
            check(runCatching{engine.setParams("{\"scene_curve\":true}")}.isFailure)
            check(engine.render("live").rgba16.contentEquals(baseline.rgba16))
            val metered=p.copy().apply {autoExposure=true}
            engine.open(input,w,h,3,metered.openJson(w,h))
            val before=JSONObject(engine.solve("both"))
            val latitudeBefore=JSONObject(engine.sceneLatitude())
            val mediumBefore=latitudeBefore.get("medium").toString()
            val histogramBefore=latitudeBefore.getJSONObject("scene").getJSONObject("histogram")
            val fractions=histogramBefore.getJSONArray("fractions")
            check(fractions.length()>32 && abs((0 until fractions.length()).sumOf {fractions.getDouble(it)}-1.0)<1e-6)
            metered.sceneCurves=shaped;engine.setChangedParams(metered.deltaJson(w,h))
            val after=JSONObject(engine.solve("both"));val mediumAfter=JSONObject(engine.sceneLatitude()).get("medium").toString()
            check(before.toString()==after.toString()) {"Scene curve changed automatic metering: $before / $after"}
            check(mediumBefore==mediumAfter) {"Scene curve changed the film's reference window"}
            engine.open(input,w,h,3,metered.openJson(w,h))
            val freshLatitude=JSONObject(engine.sceneLatitude())
            check(freshLatitude.getJSONObject("scene").getJSONObject("histogram").toString()==histogramBefore.toString()) {"Scene curve polluted pre-curve latitude histogram"}
            val autoGain=2.0.pow(before.getJSONObject("solved_params").getDouble("exposure_compensation_ev")).toFloat()
            val autoResult=engine.render("full")
            val meteredSource=FloatArray(input.size){(input[it]*autoGain).toFloat()}
            val shapedSource=apply(meteredSource,shaped,metered.exposureEv,doubleArrayOf(.2126729,.7151522,.0721750))
            val explicit=metered.copy().apply {autoExposure=false;sceneCurves=SceneCurves.IDENTITY}
            engine.open(shapedSource,w,h,3,explicit.openJson(w,h))
            val autoReference=engine.render("full")
            var autoDelta=0
            for(i in autoResult.rgba16.indices)autoDelta=maxOf(autoDelta,abs((autoResult.rgba16[i].toInt() and 65535)-(autoReference.rgba16[i].toInt() and 65535)))
            check(autoDelta<=8){"Curve/auto-exposure order drift $autoDelta"}
            maximum=maxOf(maximum,autoDelta);samples+=w*h

        }
        File(output,"scene-native.json").writeText(JSONObject().put("pixels",samples).put("max_u16_error",maximum).toString(2))
        return "$samples full film pixels vs independently transformed linear source; max U16 error $maximum; shoot invalidation/reset, print reprint, bounded wire validation, unchanged meter, medium window and fresh-session latitude histogram"
    }
}
