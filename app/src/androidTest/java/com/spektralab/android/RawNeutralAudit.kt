package com.spektralab.android

import android.app.Instrumentation
import android.net.Uri
import com.spektralab.android.core.color.NeutralWhiteBalance
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.ExportOptions
import com.spektralab.android.data.export.ExportSourceDecoder
import com.spektralab.android.engine.CameraWhiteBalance
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.RawDecoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.ln

internal class RawNeutralAudit(private val test: Instrumentation,private val output:File) {
    fun run():String {
        val rows=JSONArray()
        for(name in listOf("pc-parity-DSC_5099.NEF","perf-z6ii.NEF")) {
            val file=File(output,name);check(file.isFile)
            val uri=Uri.fromFile(file)
            val info=checkNotNull(RawDecoder.readCameraInfo(test.targetContext,uri))
            val before=RawDecoder.neutralPreview(test.targetContext,uri,null,null)
            check(maxOf(before.width,before.height)<=1024 && before.calibration[12]==3f)
            val shot=checkNotNull(CameraWhiteBalance.asShot(before.cameraXyz,before.cameraMultipliers))
            var best:Triple<Double,Double,CameraWhiteBalance.Balance>?=null
            for(y in 2..17) for(x in 2..17) {
                val px=x/20.0;val py=y/20.0
                val patch=NeutralWhiteBalance.sample(before.width,before.height,px,py) { sx,sy ->
                    DoubleArray(3){before.rgb[(sy*before.width+sx)*3+it].toDouble()}
                }?:continue
                val balance=CameraWhiteBalance.neutral(patch.rgb,before.calibration,before.cameraXyz)?:continue
                if(best==null || abs(balance.temperature-5500)<abs(best!!.third.temperature-5500)) best=Triple(px,py,balance)
            }
            val (x,y,balance)=checkNotNull(best) { "No valid neutral patch for $name" }
            val params=EditorParams().apply {cameraWbCustom=true;cameraWbKelvin=balance.temperature.toFloat();cameraWbTint=balance.tint.toFloat()}
            val mul=checkNotNull(CameraWhiteBalance.resolve(params,before.cameraXyz,before.cameraMultipliers))
            val after=RawDecoder.neutralPreview(test.targetContext,uri,mul,null)
            val patch=checkNotNull(NeutralWhiteBalance.sample(after.width,after.height,x,y){sx,sy->DoubleArray(3){after.rgb[(sy*after.width+sx)*3+it].toDouble()}})
            val residual=maxOf(abs(ln(patch.rgb[0]/patch.rgb[1])),abs(ln(patch.rgb[2]/patch.rgb[1])))
            check(residual<.04) { "$name neutral residual=$residual at $x,$y" }
            EngineClient.create(test.targetContext).use { engine ->
            for(temperatureShot in listOf(false,true)) for(tintShot in listOf(false,true)) {
                val p=params.copy().apply {cameraWbTemperatureAsShot=temperatureShot;cameraWbTintAsShot=tintShot}
                val resolved=CameraWhiteBalance.resolve(p,info.copyOfRange(0,9),info.copyOfRange(9,13))
                p.grainActive=false; p.glareActive=false
                RawDecoder.decodeForExport(test.targetContext,uri,128,resolved).use {
                    check(it.copySmallLinearRgb().any { value->value>0f })
                    engine.openRaw(it,p.openJson(it.width,it.height,"sRGB","ProPhoto RGB"))
                }
                val expected=engine.render("full")
                ExportSourceDecoder.open(test.targetContext,engine,uri,true,p,ExportOptions("PNG",128,"sRGB",95,false,false,false)) {}
                val actual=engine.render("full")
                check(actual.width==expected.width && actual.height==expected.height && actual.rgba16.contentEquals(expected.rgba16)) {
                    "Export host decoder differs from direct axis mode $temperatureShot/$tintShot for $name"
                }
            }
            }
            rows.put(JSONObject().put("source",name).put("x",x).put("y",y).put("temperature",balance.temperature)
                .put("tint",balance.tint).put("residual",residual).put("as_shot_temperature",shot.temperature).put("as_shot_tint",shot.tint))
        }
        File(output,"neutral-raw-results.json").writeText(rows.toString(2))
        return "Two real Nikon NEFs: native calibrated grey inversion re-decodes neutral within4%; all four independent axis modes export U16-exact to direct decoded engine references; bounded oriented source preview"
    }
}
