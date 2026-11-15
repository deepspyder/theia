package com.spektralab.android.data.export

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.AtomicFile
import com.spektralab.android.core.model.ExportFrameRequest
import org.json.JSONObject
import java.io.File

/** Private request/receipt journal; source pixels and copied EXIF/GPS fields are never stored here. */
internal class ExportJobStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "export-jobs").apply { mkdirs() }

    enum class State { QUEUED, PREPARING, RENDERING, ENCODING, PUBLISHING, SUCCEEDED, FAILED, CANCELLED }

    @Synchronized
    fun write(request: ExportFrameRequest, state: State, destination: String? = null, error: String? = null) {
        val data = JSONObject().put("request", JSONObject(request.toJson())).put("state", state.name)
            .put("updated", System.currentTimeMillis()).put("destination", destination ?: JSONObject.NULL)
            .put("error", error?.take(2048) ?: JSONObject.NULL)
        val atomic = AtomicFile(File(directory, "${request.jobId}.json"))
        val output = atomic.startWrite()
        try { output.write(data.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        if (state in TERMINAL) prune()
    }

    /** Called before accepting this process's first job. Never retries rendering or guesses by filename. */
    @Synchronized
    fun recover() {
        directory.listFiles { file -> file.name.matches(Regex("[a-f0-9-]{36}\\.json")) }?.forEach { file ->
            runCatching {
                val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                val state = State.valueOf(json.getString("state"))
                if (state in TERMINAL) return@runCatching
                val request = ExportFrameRequest.fromJson(json.getJSONObject("request").toString())
                val destination = json.optString("destination").takeUnless { it.isEmpty() || it == "null" }
                var completed = false
                if (destination?.startsWith("content://media/") == true && Build.VERSION.SDK_INT >= 29) {
                    val uri = Uri.parse(destination)
                    app.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            if (cursor.getInt(0) == 1) app.contentResolver.delete(uri, null, null)
                            else completed = true // Commit finished before the process could record its receipt.
                        }
                    }
                } else if (destination != null && !destination.contains("://")) {
                    val pending = File(destination)
                    val expected = (app.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: app.filesDir).canonicalFile
                    if (pending.parentFile?.canonicalFile == expected && pending.name.startsWith("Theia_") && pending.name.endsWith(".pending")) {
                        if (pending.isFile) pending.delete()
                        else completed = File(expected, pending.name.removeSuffix(".pending")).isFile
                    }
                }
                write(request, if (completed) State.SUCCEEDED else State.FAILED, destination, if (completed) null else "Export interrupted by process exit")
            }.onFailure { android.util.Log.w("TheiaExport", "Cannot recover export receipt", it) }
        }
        prune()
    }

    private fun prune() {
        // Keep bounded diagnostics; active records are never pruned.
        directory.listFiles { file -> file.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(32)?.forEach { file ->
            runCatching {
                val json = JSONObject(file.readText())
                if (State.valueOf(json.getString("state")) in TERMINAL) file.delete()
            }
        }
    }

    private companion object { val TERMINAL = setOf(State.SUCCEEDED, State.FAILED, State.CANCELLED) }
}
