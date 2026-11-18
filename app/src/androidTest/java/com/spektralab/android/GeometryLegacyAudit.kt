package com.spektralab.android

import android.app.Instrumentation
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.StockCatalog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONObject

/** Uses only the R05 public ABI, so the very same audit APK can capture the previous host. */
internal class GeometryLegacyAudit(private val test: Instrumentation, private val output: File) {
    fun run(seed: Boolean): String {
        StockCatalog.load(test.targetContext)
        val report = JSONObject()
        EngineClient.create(test.targetContext).use { engine ->
            val w=383;val h=257
            val input=FloatArray(w*h*3) { i -> when(i%3) {0->(i/3%w+.5f)/w;1->(i/3/w+.5f)/h;else->.18f} }
            for(turn in 0..3) for(frame in listOf(false,true)) {
                val p=EditorParams().apply {grainActive=false;halationActive=false;glareActive=false;quarterTurns=turn
                    flipH=turn%2==0;rotationDeg=3f;overscanActive=frame;dateImprintActive=frame;dateImprintText="26 11 18"}
                engine.open(input,w,h,3,p.openJson(w,h))
                engine.renderExportOnce().use { result ->
                    val hash=MessageDigest.getInstance("SHA-256")
                    val row=ShortArray(result.width*4);val bytes=ByteBuffer.allocate(row.size*2).order(ByteOrder.LITTLE_ENDIAN)
                    for(y in 0 until result.height){result.readRows(y,1,row);bytes.clear();bytes.asShortBuffer().put(row);hash.update(bytes.array())}
                    report.put("$turn-$frame", "${result.width}x${result.height}:"+hash.digest().joinToString(""){"%02x".format(it)})
                }
            }
        }
        val baseline=File(output,"geometry-r05-baseline.json")
        if(seed) baseline.writeText(report.toString(2)) else {
            val expected=JSONObject(baseline.readText())
            for(key in report.keys()) check(report.getString(key)==expected.getString(key)) { "Legacy pixel drift $key" }
            File(output,"geometry-r06-legacy.json").writeText(report.toString(2))
        }
        return "8 full U16 rasters, turns/flips/straighten, border/date: ${if(seed) "R05 baseline saved" else "bitwise R05 parity"}"
    }
}
