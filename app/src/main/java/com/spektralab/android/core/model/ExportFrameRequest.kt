package com.spektralab.android.core.model

import org.json.JSONObject
import java.util.UUID

/** Owned, versioned request. No live editor state or pixel buffers cross the host boundary. */
internal data class ExportFrameRequest(
    val jobId: String,
    val sourceHash: String,
    val sourceName: String,
    val raw: Boolean,
    val edit: EditSnapshot,
    val options: ExportOptions,
    val decoderIdentity: String,
    val rendererIdentity: String,
    val realisation: ExportRealisation = ExportRealisation.fresh(edit.toParams()),
) {
    init {
        require(UUID.fromString(jobId).toString() == jobId)
        require(sourceHash.matches(Regex("[a-f0-9]{64}")))
        require(sourceName.length <= 512)
        require(options.resolution >= 0)
    }

    fun toJson(): String = JSONObject().apply {
        put("schema", 2); put("job_id", jobId); put("source_sha256", sourceHash)
        put("source_name", sourceName); put("raw", raw); put("edit", JSONObject(edit.json))
        put("decoder", decoderIdentity); put("renderer", rendererIdentity)
        put("random_version", 1); put("film_seed", realisation.film); put("print_seed", realisation.print)
        put("options", JSONObject().apply {
            put("format", options.format); put("resolution", options.resolution)
            put("color_space", options.colorSpace); put("jpeg_quality", options.jpegQuality)
            put("keep_capture", options.keepCapture); put("keep_location", options.keepLocation)
            put("correct_lens", options.correctLens)
        })
    }.toString()

    companion object {
        fun fromJson(value: String): ExportFrameRequest {
            require(value.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "Export request exceeds IPC limit" }
            val json = JSONObject(value)
            val schema = json.getInt("schema")
            require(schema in 1..2) { "Unsupported export request" }
            require(schema == 1 || json.getInt("random_version") == 1) { "Unsupported random realisation" }
            val options = json.getJSONObject("options")
            val edit = EditSnapshot.fromJson(json.getJSONObject("edit"))
            return ExportFrameRequest(json.getString("job_id"), json.getString("source_sha256"),
                json.getString("source_name"), json.getBoolean("raw"),
                edit,
                ExportOptions(options.getString("format"), options.getInt("resolution"), options.getString("color_space"),
                    options.getInt("jpeg_quality"), options.getBoolean("keep_capture"), options.getBoolean("keep_location"),
                    options.getBoolean("correct_lens")), json.getString("decoder"), json.getString("renderer"),
                if (json.has("film_seed")) ExportRealisation(json.getLong("film_seed"), json.getLong("print_seed"))
                else ExportRealisation.fresh(edit.toParams()))
        }
    }
}
