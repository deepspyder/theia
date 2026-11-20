package com.spektralab.android

import android.app.Instrumentation
import android.content.ContextWrapper
import com.spektralab.android.core.color.OutputColorSpaces
import com.spektralab.android.core.color.OutputIcc
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.data.local.RecipeStore
import com.spektralab.android.engine.EditorGrade
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.Rgba16Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.InflaterInputStream

/** Real codecs, profiles and all U16 samples; desktop audit independently checks colour science. */
internal class ProfessionalOutputAudit(private val test: Instrumentation, output: File) {
    private val directory = File(output, "r08-output").apply { mkdirs() }
    private val width = 257
    private val height = 131
    private val pixels = ShortArray(width * height * 4) { i ->
        if (i % 4 == 3) 65535.toShort() else ((i * 19357L + 32771) % 65536).toShort()
    }
    private val source = object : Rgba16Source {
        override val width = this@ProfessionalOutputAudit.width
        override val height = this@ProfessionalOutputAudit.height
        override fun readRows(y: Int, rows: Int, destination: ShortArray) {
            check(rows <= 32)
            pixels.copyInto(destination, 0, y * width * 4, (y + rows) * width * 4)
        }
    }

    fun run(): String {
        models()
        headers()
        val identity = EditorGrade.identity
        val adjusted = EditorGrade.from(EditorParams().apply { brightness = 8f; vignetteAmount = 13f })
        val metadata = ExportMetadata(mapOf("Make" to "TheiaAudit", "DateTimeOriginal" to "2026:11:19 12:34:56",
            "GPSLatitudeRef" to "N", "GPSLatitude" to "1/1,2/1,3/1", "GPSLongitudeRef" to "E", "GPSLongitude" to "4/1,5/1,6/1"))
        val timings = JSONArray()
        for ((g, grade) in listOf(identity, adjusted).withIndex()) {
            File(directory, "reference-$g.tiff").outputStream().use { TiffWriter.writeNative(source, grade, null, it, {}) }
            for ((c, name) in OutputColorSpaces.names.withIndex()) {
                val icc = OutputIcc.generate(name)
                File(directory, "space-$c.icc").writeBytes(icc)
                val converted = OutputConvertedSource.wrap(source, grade, "sRGB", name) {}
                val outputGrade = if (converted.gradeApplied) identity else grade
                if (c == 0) check(converted === source) else {
                    val full = ShortArray(width * height * 4)
                    converted.readRows(0, height, full)
                    for (y in listOf(0, 1, 63, 130)) {
                        val row = ShortArray(width * 4); converted.readRows(y, 1, row)
                        check(row.contentEquals(full.copyOfRange(y * width * 4, (y + 1) * width * 4)))
                    }
                    check(runCatching { converted.readRows(-1, 1, ShortArray(width * 4)) }.isFailure)
                    check(runCatching { converted.readRows(0, 1, ShortArray(1)) }.isFailure)
                    check(runCatching {
                        OutputConvertedSource.wrap(source, grade, "sRGB", name) { error("cancel-colour") }
                            .readRows(0, 1, ShortArray(width * 4))
                    }.exceptionOrNull()?.message == "cancel-colour")
                }
                for (format in listOf("png", "jpg", "tiff", "deflate.tiff")) {
                    val start = android.os.SystemClock.elapsedRealtime()
                    val file = File(directory, "g$g-c$c.$format")
                    file.outputStream().use { out -> when (format) {
                        "png" -> PngRowWriter.write(converted, outputGrade, out, {}, metadata, if (c == 0) null else icc)
                        "jpg" -> JpegRowWriter.write(test.targetContext, converted, outputGrade, 95, out, {}, metadata, if (c == 0) null else icc)
                        "tiff" -> TiffWriter.writeNative(converted, outputGrade, icc, out, {}, metadata)
                        else -> DeflatedTiffWriter.write(test.targetContext, converted, outputGrade, icc, out, {}, metadata)
                    } }
                    timings.put(JSONObject().put("file",file.name).put("ms",android.os.SystemClock.elapsedRealtime()-start).put("bytes",file.length()))
                }
                val plain = File(directory, "g$g-c$c.tiff").readBytes()
                val packed = File(directory, "g$g-c$c.deflate.tiff").readBytes()
                val strip = field(plain, 273)
                check(field(plain, 259) == 1 && field(packed, 259) == 8)
                check(field(packed, 273) == strip)
                val inflated = InflaterInputStream(packed.copyOfRange(strip,packed.size).inputStream()).use { it.readBytes() }
                check(inflated.contentEquals(plain.copyOfRange(strip,plain.size))) { "Lossless TIFF changed RGB16 pixels" }
                check(field(packed,279) == packed.size-strip)
                if (c == 0) {
                    val expected = ByteArrayOutputStream().also { PngRowWriter.write(source,grade,it,{},metadata) }.toByteArray()
                    check(expected.contentEquals(File(directory,"g$g-c$c.png").readBytes())) { "Default PNG byte path changed" }
                }
            }
        }
        val before = staging()
        checkDeflateStaging(identity, metadata)
        val noSpace=object:ContextWrapper(test.targetContext) {
            override fun getCacheDir()=object:File(test.targetContext.cacheDir.path) {override fun getUsableSpace()=0L}
        }
        check(runCatching {DeflatedTiffWriter.write(noSpace,source,identity,null,ByteArrayOutputStream(),{},metadata)}
            .exceptionOrNull()?.message=="Insufficient lossless TIFF staging space")
        check(staging()==before)
        val capability = HeifOutput.capability(test.targetContext)
        val heif = JSONObject().put("available",capability.available).put("reason",capability.reason)
        if (capability.available) {
            for ((w,h) in listOf(257 to 131, 1 to 1, 1025 to 769)) {
                val odd = object:Rgba16Source {
                    override val width=w; override val height=h
                    override fun readRows(y:Int,rows:Int,destination:ShortArray) { destination.fill(30000) }
                }
                val file=File(directory,"codec-${w}x$h.heic")
                file.outputStream().use { HeifOutput.write(test.targetContext,odd,identity,95,it,{},metadata) }
                val bitmap=android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file))
                try {
                    check(bitmap.width==w && bitmap.height==h)
                    val software=bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888,false)
                    try {
                        val pixel=software.getPixel(w/2,h/2)
                        for(shift in listOf(0,8,16))check(kotlin.math.abs(((pixel ushr shift)and 255)-117)<=5){"HEIF sample not SDR sRGB: $pixel"}
                    }finally{software.recycle()}
                } finally { bitmap.recycle() }
                val exif=androidx.exifinterface.media.ExifInterface(file)
                check(exif.getAttribute("Make")=="TheiaAudit" && exif.latLong!=null)
            }
            var reads=0
            val cancelAfterCodec=object:Rgba16Source {
                override val width=32;override val height=32
                override fun readRows(y:Int,rows:Int,destination:ShortArray){reads++;destination.fill(30000)}
            }
            // Cancellation after rows are consumed: no completed file is copied/published.
            val bytes=ByteArrayOutputStream()
            var checksAfterRead=0
            check(runCatching {HeifOutput.write(test.targetContext,cancelAfterCodec,identity,95,bytes,{
                if(reads>0 && ++checksAfterRead>=3)error("cancel-heif")
            },metadata)}.exceptionOrNull()?.message=="cancel-heif")
            check(bytes.size()==0 && staging()==before)
            check(runCatching {HeifOutput.write(noSpace,source,identity,95,ByteArrayOutputStream(),{},metadata)}
                .exceptionOrNull()?.message=="Insufficient HEIF staging space")
        } else {
            check(runCatching { HeifOutput.write(test.targetContext,source,identity,95,ByteArrayOutputStream(),{},metadata) }
                .exceptionOrNull()?.message == "HEIF_CODEC_UNAVAILABLE")
        }
        check(staging()==before)
        File(directory,"timings.json").writeText(timings.toString(2))
        File(directory,"heif.json").writeText(heif.toString(2))
        return "R08: 32 actual files, 4 ICC profiles, exact RGB16 Deflate parity, bounded rows, single unlinked strip, mid-compression cancellation and copy failure cleanup, legacy migration/IPC and real HEIF probe=${capability.available}"
    }

    private fun staging() = test.targetContext.cacheDir.listFiles().orEmpty()
        .filter { it.name.startsWith(".tiff-") || it.name.startsWith(".heif-") || it.name.startsWith("export-fd-") }
        .map { it.name }.toSet()

    private fun checkDeflateStaging(grade: Grade, metadata: ExportMetadata) {
        val before = staging()
        val openStaging = {
            File("/proc/self/fd").listFiles().orEmpty().mapNotNull {
                runCatching { android.system.Os.readlink(it.path) }.getOrNull()
            }.filter { it.contains("/export-fd-") }
        }
        val descriptorsBefore = openStaging()
        val rawBytes = width.toLong() * height * 6
        val context = object : ContextWrapper(test.targetContext) {
            override fun getCacheDir(): File = object : File(test.targetContext.cacheDir.path) {
                // Enough for zlib overhead, insufficient for the old two-raster calculation.
                override fun getUsableSpace() = 64L * 1024 * 1024 + rawBytes + 1024
            }
        }
        var rowsRead = 0
        val tracked = object : Rgba16Source by source {
            override fun readRows(y: Int, rows: Int, destination: ShortArray) {
                check(y == rowsRead)
                val open = openStaging() - descriptorsBefore.toSet()
                check(open.size == 2 && open.toSet().size == 1 && open.all { it.endsWith(" (deleted)") })
                check(staging() == before)
                rowsRead += rows
                source.readRows(y, rows, destination)
            }
        }
        val packed = ByteArrayOutputStream()
        DeflatedTiffWriter.write(context, tracked, grade, null, packed, {}, metadata)
        check(rowsRead == height && field(packed.toByteArray(), 259) == 8)
        check(staging() == before && openStaging() == descriptorsBefore)

        rowsRead = 0
        val cancelled = ByteArrayOutputStream()
        check(runCatching {
            DeflatedTiffWriter.write(context, tracked, grade, null, cancelled, {
                if (rowsRead > 0) error("cancel-tiff")
            }, metadata)
        }.exceptionOrNull()?.message == "cancel-tiff")
        check(cancelled.size() == 0 && rowsRead in 1 until height)
        check(staging() == before && openStaging() == descriptorsBefore)

        val headerSize = field(packed.toByteArray(), 273)
        var written = 0
        val failingDestination = object : java.io.OutputStream() {
            override fun write(value: Int) {
                if (written >= headerSize) throw java.io.IOException("io-tiff")
                written++
            }
        }
        check(runCatching {
            DeflatedTiffWriter.write(context, source, grade, null, failingDestination, {}, metadata)
        }.exceptionOrNull()?.message == "io-tiff")
        check(written == headerSize && staging() == before && openStaging() == descriptorsBefore)
    }

    private fun field(bytes:ByteArray,tag:Int):Int {
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val root=b.getInt(4); val n=b.getShort(root).toInt() and 65535
        val entry=(0 until n).map { root+2+it*12 }.first { b.getShort(it).toInt() and 65535 == tag }
        return if (b.getShort(entry+2).toInt()==3) b.getShort(entry+8).toInt() and 65535 else b.getInt(entry+8)
    }

    private fun models() {
        val dir=File(test.targetContext.cacheDir,"r08-recipes-${System.nanoTime()}").apply { mkdirs() }
        val context=object:ContextWrapper(test.targetContext) { override fun getFilesDir()=dir }
        try {
            val legacy=JSONObject().put("name","Old").put("format","PNG").put("res",0).put("color_space","Display P3").put("unowned",42)
            val file=File(dir,"recipes.json"); file.writeText(JSONArray().put(legacy).toString())
            check(RecipeStore.load(context).single().colorSpace=="sRGB")
            for (name in OutputColorSpaces.names) {
                val recipe=RecipeStore.load(context).single().copy(colorSpace=name,tiffDeflate=true)
                RecipeStore.save(context,listOf(recipe)); check(RecipeStore.load(context).single()==recipe)
                check(JSONArray(file.readText()).getJSONObject(0).getInt("unowned")==42)
                val options=ExportOptions("PNG",0,name,95,true,false,true,tiffDeflate=true)
                val request=ExportFrameRequest(UUID.randomUUID().toString(),"0".repeat(64),"test.jpg",false,
                    EditSnapshot.capture(EditorParams()),options,"decoder","renderer")
                check(ExportFrameRequest.fromJson(request.toJson())==request)
                for (schema in 1..3) {
                    val old=JSONObject(request.toJson()).put("schema",schema)
                    old.getJSONObject("options").remove("tiff_deflate")
                    val restored=ExportFrameRequest.fromJson(old.toString())
                    check(restored.options.colorSpace=="sRGB" && !restored.options.tiffDeflate)
                }
            }
            check(runCatching { ExportOptions("HEIF",0,"Display P3",95,true,false,true) }.isFailure)
        } finally { dir.deleteRecursively() }
    }

    private fun headers() {
        val jpeg=byteArrayOf(-1,-40,-1,-32,0,4,3,4,-1,-39)
        for (size in listOf(1,65519,65520,131038,131039)) {
            val icc=ByteArray(size) { (it*17).toByte() }; val out=ByteArrayOutputStream()
            val stream=jpegHeaders(out,null,icc) {}
            jpeg.forEach { stream.write(it.toInt() and 255) }
            val bytes=out.toByteArray(); var at=2; var sequence=1; val rebuilt=ByteArrayOutputStream()
            val count=(size+65518)/65519
            while (bytes[at].toInt() and 255 == 255 && bytes[at+1].toInt() and 255 == 226) {
                val length=((bytes[at+2].toInt() and 255) shl 8) or (bytes[at+3].toInt() and 255)
                check(String(bytes,at+4,11,Charsets.US_ASCII)=="ICC_PROFILE")
                check(bytes[at+15].toInt()==0 && bytes[at+16].toInt() and 255 == sequence++)
                check(bytes[at+17].toInt() and 255 == count)
                rebuilt.write(bytes,at+18,length-16); at+=length+2
            }
            check(rebuilt.toByteArray().contentEquals(icc) && sequence==count+1)
            check(bytes.copyOfRange(at,bytes.size).contentEquals(jpeg.copyOfRange(2,jpeg.size)))
        }
        check(runCatching { jpegHeaders(ByteArrayOutputStream(),null,null) {}.write(byteArrayOf(1,2)) }.isFailure)
        // A platform sRGB APP2 must be removed when replacing it, while XMP/unknown APP2 stay.
        val old=ByteArrayOutputStream();jpegHeaders(old,null,ByteArray(70000){1}){}.write(jpeg)
        val replaced=ByteArrayOutputStream();jpegHeaders(replaced,null,byteArrayOf(9,8,7)){}.write(old.toByteArray())
        val expected=ByteArrayOutputStream();jpegHeaders(expected,null,byteArrayOf(9,8,7)){}.write(jpeg)
        check(replaced.toByteArray().contentEquals(expected.toByteArray()))
    }
}
