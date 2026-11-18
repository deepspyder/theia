package com.spektralab.android

import android.net.Uri
import android.os.Process
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.library.EditHistoryRepository
import com.spektralab.android.data.library.SavedPhotoRepository
import org.json.JSONObject
import java.io.File

/** Deliberately kills this instrumentation's app process inside real WAL transactions. */
internal class PersistentHistoryCrashAudit(private val output: File) {
    fun run(phase: String): String {
        val record = File(output, "history-crash.json")
        val directory = if (phase == "seed") File(output, "history-crash-${System.nanoTime()}")
            else File(JSONObject(record.readText()).getString("path"))
        val uri = Uri.parse("content://history-crash/source")
        fun json(ev: Float) = EditorParams().apply { exposureEv = ev }.toJson().toString()
        SavedPhotoRepository.open(directory).use { repo ->
            if (phase == "seed") {
                repo.save(uri, json(0f), "crash")
                repo.save(uri, json(1f), "")
                record.writeText(JSONObject().put("path", directory.path).toString())
                return "WAL baseline and first edit committed"
            }
            val before = repo.historyPosition(uri, json(0f), "")
            if (phase.startsWith("kill-")) {
                val point = when (phase) {
                    "kill-step" -> EditHistoryRepository.Checkpoint.STEP_WRITTEN
                    "kill-cursor" -> EditHistoryRepository.Checkpoint.CURSOR_MOVED
                    else -> EditHistoryRepository.Checkpoint.EDIT_WRITTEN
                }
                repo.history.checkpoint = {
                    if (it == point) {
                        File(output, "history-crash-entered.txt").writeText(phase)
                        Process.killProcess(Process.myPid())
                        error("Process kill returned")
                    }
                }
                if (phase == "kill-step" || phase == "kill-save-edit") repo.save(uri, json(2f), "")
                else repo.moveHistory(uri, before.generation, false)
                error("History checkpoint was not reached")
            }
            if (phase == "verify") {
                check(JSONObject(before.paramsJson).getDouble("exposure_compensation_ev") == 1.0)
                check(before.canUndo && !before.canRedo && before.retainedStates == 2)
                check(repo.read(uri) == before.paramsJson)
                return "Process death rolled back current edit, compressed step and cursor together"
            }
            if (phase == "commit-undo") {
                repo.moveHistory(uri, before.generation, false)
                return "Undo committed before process exit"
            }
            if (phase == "verify-undo") {
                check(JSONObject(before.paramsJson).getDouble("exposure_compensation_ev") == 0.0)
                check(!before.canUndo && before.canRedo && before.retainedStates == 2)
                val restored = repo.moveHistory(uri, before.generation, true)
                check(JSONObject(restored.paramsJson).getDouble("exposure_compensation_ev") == 1.0)
                return "Committed undo cursor and redo survive process death"
            }
            error("Unknown crash phase")
        }
    }
}
