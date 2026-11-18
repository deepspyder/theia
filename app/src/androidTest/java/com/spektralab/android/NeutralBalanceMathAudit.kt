package com.spektralab.android

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.spektralab.android.core.color.NeutralWhiteBalance
import com.spektralab.android.core.model.CanonicalJson
import com.spektralab.android.core.model.EditGroup
import com.spektralab.android.core.model.EditTemplate
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.library.SavedPhotoRepository
import com.spektralab.android.engine.CameraWhiteBalance
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

internal class NeutralBalanceMathAudit(private val test: Instrumentation, private val output: File) {
    fun run(): String {
        var samples = 0
        for (temperature in listOf(-1.0, -.7, -.1, 0.0, .4, .9, 1.0))
            for (tint in listOf(-1.0, -.5, 0.0, .3, 1.0)) for (grey in listOf(.03, .18, .6)) {
                val rgb = doubleArrayOf(grey / (1 + .18 * temperature), grey / (1 - .12 * tint), grey / (1 - .18 * temperature))
                val result = checkNotNull(NeutralWhiteBalance.printBalance(rgb))
                check(abs(result.temperature - temperature) < 1e-12 && abs(result.tint - tint) < 1e-12)
                val neutral = doubleArrayOf(rgb[0]*(1+.18*result.temperature),rgb[1]*(1-.12*result.tint),rgb[2]*(1-.18*result.temperature))
                check(neutral.all { abs(it - grey) < 1e-12 }); samples++
            }
        for (rgb in listOf(doubleArrayOf(0.0,.2,.2),doubleArrayOf(.2,Double.NaN,.2),doubleArrayOf(1.0,.2,.2)))
            check(NeutralWhiteBalance.printBalance(rgb) == null)
        check(checkNotNull(NeutralWhiteBalance.printBalance(doubleArrayOf(.05,.3,.8))).limited)
        for ((vw,vh) in listOf(400f to 800f,800f to 400f,1f to 1f))
            for ((iw,ih) in listOf(601 to 403,403 to 601,1 to 517)) {
                val center = checkNotNull(NeutralWhiteBalance.imagePoint(vw,vh,iw,ih,vw/2,vh/2))
                check(abs(center.first-.5)<1e-5 && abs(center.second-.5)<1e-5)
                check(NeutralWhiteBalance.imagePoint(vw,vh,iw,ih,-1f,-1f)==null)
            }
        check(NeutralWhiteBalance.sample(11,9,.5,.5) { _,_->doubleArrayOf(.2,.3,.4) }!!.pixels == 49)
        check(NeutralWhiteBalance.sample(11,9,0.0,0.0) { _,_->doubleArrayOf(.2,.3,.4) }!!.pixels == 16)
        check(NeutralWhiteBalance.sample(11,9,.5,.5) { _,_->null } == null)
        check(NeutralWhiteBalance.sample(11,9,1.01,.5) { _,_->doubleArrayOf(.2,.3,.4) } == null)
        check(NeutralWhiteBalance.imagePoint(Float.NaN, 400f, 11, 9, 2f, 2f) == null)
        val camera = floatArrayOf(.7f,.2f,.1f,.1f,.8f,.1f,.1f,.1f,.8f)
        for (temperature in listOf(2500.0,3500.0,5500.0,7500.0,12000.0,30000.0)) for (tint in listOf(-60.0,0.0,60.0)) {
            val xy=DoubleArray(2);CameraWhiteBalance.temperatureTintToXy(temperature,tint,xy)
            // This extreme synthetic illuminant has negative Z, so it cannot be sampled from RGB.
            if (temperature == 2500.0 && tint == 60.0) {
                check(xy[0] + xy[1] >= 1 && CameraWhiteBalance.fromXy(xy[0], xy[1]) == null)
                continue
            }
            val actual=checkNotNull(CameraWhiteBalance.fromXy(xy[0],xy[1]))
            check(abs(actual.temperature-temperature)<maxOf(25.0,temperature*.015)) { "$temperature/$tint -> $actual" }
            check(abs(actual.tint-tint)<2.5) { "$temperature/$tint -> $actual" }
            val multipliers=FloatArray(4)
            check(CameraWhiteBalance.multipliersFor(camera,temperature,tint,multipliers))
            val shot=checkNotNull(CameraWhiteBalance.asShot(camera,multipliers))
            check(abs(shot.temperature-temperature)<maxOf(25.0,temperature*.015))
            val neutral=doubleArrayOf(xy[0]/xy[1],1.0,(1-xy[0]-xy[1])/xy[1])
            val calibration=FloatArray(13).apply { this[0]=1f;this[4]=1f;this[8]=1f;this[9]=1f;this[10]=1f;this[11]=1f;this[12]=3f }
            val cameraPatch=DoubleArray(3){row->(0..2).sumOf { camera[row*3+it]*neutral[it] }*.05}
            val found=checkNotNull(CameraWhiteBalance.neutral(cameraPatch,calibration,camera))
            check(abs(found.temperature-temperature)<maxOf(25.0,temperature*.015));samples++
        }
        val legacy = EditorParams().apply { cameraWbCustom=true;cameraWbKelvin=6100f;cameraWbTint=11f }
        val old=FloatArray(4);check(CameraWhiteBalance.multipliersFor(camera,6100.0,11.0,old))
        check(CameraWhiteBalance.resolve(legacy,camera,null)!!.contentEquals(old))
        check(CameraWhiteBalance.resolve(legacy, FloatArray(9), null) == null)
        check(CameraWhiteBalance.neutral(doubleArrayOf(.2,.2,.2), FloatArray(13), camera) == null)
        val shotMul=FloatArray(4);check(CameraWhiteBalance.multipliersFor(camera,5500.0,0.0,shotMul))
        for (temperatureShot in listOf(false,true)) for (tintShot in listOf(false,true)) {
            val p=legacy.copy().apply { cameraWbTemperatureAsShot=temperatureShot;cameraWbTintAsShot=tintShot }
            val actual=CameraWhiteBalance.resolve(p,camera,shotMul)
            if(temperatureShot&&tintShot) check(actual==null)
            else check(actual!=null && actual.all { it.isFinite() && it>0 })
            check(EditorParams.fromJson(p.toJson()).cameraWbTemperatureAsShot==temperatureShot)
            val template=EditTemplate.fromJson(EditTemplate.capture(p,setOf(EditGroup.DECODE)).toJson())
            check(template.toJson().getJSONObject("values").getBoolean("camera_wb_tint_as_shot")==tintShot)
        }
        val oldTemplate=EditTemplate.capture(legacy,setOf(EditGroup.DECODE)).toJson()
        oldTemplate.getJSONObject("values").remove("camera_wb_temperature_as_shot")
        oldTemplate.getJSONObject("values").remove("camera_wb_tint_as_shot")
        EditTemplate.fromJson(oldTemplate)
        legacyHistoryDigest()
        return "$samples independent neutral/temperature samples; Fit/patch bounds, invalid/clipped data, axis modes, old templates and R03 stored history digest compatibility passed"
    }

    private fun legacyHistoryDigest() {
        val directory=File(output,"neutral-legacy-history-${System.nanoTime()}")
        val uri=Uri.parse("content://neutral/legacy")
        try {
            SavedPhotoRepository.open(directory).use { repo ->
                val old=EditorParams().toJson().apply { remove("camera_wb_temperature_as_shot");remove("camera_wb_tint_as_shot") }.toString()
                val state=checkNotNull(repo.save(uri,old,"legacy"))
                val r03=EditorParams.fromJson(JSONObject(old)).toJson().apply {
                    remove("overscan_edge_text");remove("camera_wb_temperature_as_shot");remove("camera_wb_tint_as_shot")
                }
                val digest=CanonicalJson.fingerprint(CanonicalJson.encode(r03))
                val db=SavedPhotoRepository::class.java.getDeclaredField("db").apply { isAccessible=true }.get(repo) as SQLiteDatabase
                db.execSQL("UPDATE edit_history_steps SET hash=? WHERE edit_id=?",arrayOf(digest,state.editId))
                val restored=repo.historyPosition(uri,old,"")
                check(restored.generation==state.generation && !restored.canUndo)
            }
        } finally {directory.deleteRecursively()}
    }
}
