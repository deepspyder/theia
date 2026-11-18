package com.spektralab.android

import android.app.Instrumentation
import android.net.Uri
import android.os.SystemClock
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.ExportOptions
import com.spektralab.android.core.model.ExportRealisation
import com.spektralab.android.data.export.ExportSourceDecoder
import com.spektralab.android.data.export.PngRowWriter
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.NativeRenderResult
import com.spektralab.android.engine.StockCatalog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Full camera frame plus physical film canvas, compared with the independent whole dispatch. */
internal class OverscanExportAudit(private val test: Instrumentation, private val output: File) {
    fun native(): String {
        val context = test.targetContext
        StockCatalog.load(context)
        val source = File(output, "pc-parity-DSC_5099.NEF")
        check(source.isFile)
        val report = JSONArray()
        var failures = 0
        for (format in listOf("135", "135_half", "135_xpan")) {
            val params = EditorParams().apply {
                filmId = "cinestill_800t"; paperId = "kodak_2383"; halationModelVersion = 2
                overscanActive = true; overscanFormat = format
                dateImprintActive = true; dateImprintText = "'26 11 17"
            }
            val record = JSONObject().put("format", format)
            try {
                for (windowed in listOf(false, true)) {
                    val prefix = "overscan-$format-${if (windowed) "windowed" else "whole"}"
                    EngineClient.create(context).use { engine ->
                        val start = SystemClock.elapsedRealtime()
                        ExportSourceDecoder.open(context, engine, Uri.fromFile(source), true, params,
                            ExportOptions("PNG", 0, "sRGB", 100, false, false, true)) {}
                        val seed = ExportRealisation.fresh(params)
                        val frame = if (windowed) engine.renderExportResolved(seed.film, seed.print) else engine.renderForExport()
                        frame.use {
                            record.put(if (windowed) "windowed" else "whole", JSONObject()
                                .put("width", it.width).put("height", it.height).put("sha256_u16", digest(it))
                                .put("render_total_ms", SystemClock.elapsedRealtime() - start))
                            File(output, "$prefix.png").outputStream().use { out ->
                                PngRowWriter.write(it, Grade(1f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f,
                                    FloatArray(3), FloatArray(3), FloatArray(3)), out, {})
                            }
                        }
                    }
                }
                val whole = record.getJSONObject("whole")
                val actual = record.getJSONObject("windowed")
                check(whole.getInt("width") == actual.getInt("width") && whole.getInt("height") == actual.getInt("height") &&
                    whole.getString("sha256_u16") == actual.getString("sha256_u16")) { "Full-size film canvas differs from whole dispatch" }
                record.put("passed", true)
            } catch (failure: Exception) {
                failures++; record.put("passed", false).put("error", failure.stackTraceToString())
            }
            report.put(record)
            File(output, "overscan-native.json").writeText(report.toString(2))
        }
        check(failures == 0) { "$failures full-size overscan cases failed; see overscan-native.json" }
        return "Full-size DSC_5099 NEF, CineStill/2383, grain/glare/physical halation/date, 135/half/XPan: whole RGBA16 identical"
    }

    private fun digest(frame: NativeRenderResult): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val values = ShortArray(frame.width * 16 * 4)
        val bytes = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until frame.height step 16) {
            val rows = minOf(16, frame.height - y)
            frame.readRows(y, rows, values)
            bytes.clear(); bytes.asShortBuffer().put(values, 0, frame.width * rows * 4)
            hash.update(bytes.array(), 0, frame.width * rows * 8)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
