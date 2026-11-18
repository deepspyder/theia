package com.spektralab.android

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import com.spektralab.android.core.model.EditHistoryPosition
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.library.EditHistoryRepository
import com.spektralab.android.data.library.SavedPhotoRepository
import com.spektralab.android.data.library.SourceProbe
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Isolated real SQLite files, including injected transaction failures and an actual page quota. */
internal class PersistentHistoryStorageAudit(private val test: Instrumentation, private val output: File) {
    private val context get() = test.targetContext
    private fun json(ev: Float, opaque: String = "kept") = EditorParams().apply { exposureEv = ev }
        .toJson().put("history_unknown", JSONObject().put("value", opaque)).toString()
    private fun ev(position: EditHistoryPosition) = JSONObject(position.paramsJson).getDouble("exposure_compensation_ev")
    private fun db(repo: SavedPhotoRepository): SQLiteDatabase = SavedPhotoRepository::class.java.getDeclaredField("db")
        .apply { isAccessible = true }.get(repo) as SQLiteDatabase

    fun run(): String {
        val root = File(output, "history-storage-${System.nanoTime()}").apply { mkdirs() }
        try {
            transitions(File(root, "transitions"))
            rollbackAndQuota(File(root, "rollback"))
            migrationAndProtection(File(root, "migration"))
            budgets(File(root, "budgets"))
        } finally { root.deleteRecursively() }
        return "Persistent history: atomic edit/cursor/branch, grouping/no-op, unknown fields, variant isolation/relink, 256-state/8MiB trim, v5 migration, future/corrupt preservation, actual SQLITE_FULL and transaction rollback passed"
    }

    private fun transitions(directory: File) {
        val original = File(directory.parentFile, "original.bin").apply { writeBytes(ByteArray(1024) { it.toByte() }) }
        val moved = File(directory.parentFile, "moved.bin").apply { writeBytes(original.readBytes()) }
        val uri = Uri.fromFile(original)
        lateinit var defaultId: String
        lateinit var copyUri: Uri
        var generation = 0L
        SavedPhotoRepository.open(directory).use { repo ->
            var state = requireNotNull(repo.save(uri, json(0f), "original"))
            defaultId = state.editId
            check(!state.canUndo && !state.canRedo && state.retainedStates == 1)
            state = requireNotNull(repo.save(uri, json(1f), "", "drag-a", "editor", state.generation))
            state = requireNotNull(repo.save(uri, json(2f), "", "drag-a", "editor", state.generation))
            check(state.retainedStates == 2 && ev(state) == 2.0)
            state = requireNotNull(repo.save(uri, json(3f), "", "drag-b", "editor", state.generation))
            state = repo.moveHistory(uri, state.generation, false)
            check(ev(state) == 2.0 && state.canUndo && state.canRedo)
            val noOp = requireNotNull(repo.save(uri, json(2f), ""))
            check(noOp.generation == state.generation && noOp.canRedo) { "Autosave erased redo" }
            state = repo.moveHistory(uri, state.generation, true)
            check(ev(state) == 3.0)
            state = repo.moveHistory(uri, state.generation, false)
            val stale = state.generation
            state = requireNotNull(repo.save(uri, json(4f), "", "branch", "editor", state.generation))
            check(!state.canRedo && state.retainedStates == 3)
            check(runCatching { repo.moveHistory(uri, stale, false) }.isFailure) { "Stale cursor accepted" }
            check(repo.read(uri) == state.paramsJson)
            check(JSONObject(state.paramsJson).getJSONObject("history_unknown").getString("value") == "kept")
            val photo = checkNotNull(repo.findByUri(uri))
            val copy = repo.versions.create(repo.versions.list(photo.assetId).first(), "Copy")
            copyUri = copy.editUri
            var copyState = repo.historyPosition(copyUri, copy.paramsJson, "")
            check(copyState.editId != defaultId && !copyState.canUndo)
            copyState = requireNotNull(repo.save(copyUri, json(7f), ""))
            copyState = repo.moveHistory(copyUri, copyState.generation, false)
            check(ev(copyState) == 4.0 && repo.read(uri) == state.paramsJson)
            check(copyState.canRedo)
            repo.checkpoint = { if (it == SavedPhotoRepository.Checkpoint.BATCH_WRITTEN) error("batch-history-rollback") }
            check(runCatching {
                repo.saveBatch(listOf(SavedPhotoRepository.PendingWrite(uri, state.paramsJson, json(5f)),
                    SavedPhotoRepository.PendingWrite(copyUri, copyState.paramsJson, json(6f))), emptyMap())
            }.isFailure)
            repo.checkpoint = {}
            check(repo.historyPosition(uri, json(0f), "") == state)
            check(repo.historyPosition(copyUri, json(0f), "") == copyState)
            repo.checkSource(photo, SourceProbe.inspect(context, uri))
            repo.relink(checkNotNull(repo.findByUri(uri)), SourceProbe.inspect(context, Uri.fromFile(moved)))
            val relocated = repo.historyPosition(Uri.fromFile(moved), json(0f), "")
            check(relocated.editId == defaultId && relocated.generation == state.generation && relocated.canUndo)
            check(runCatching { repo.save(uri, json(99f), "stale") }.isFailure)
            generation = relocated.generation
            val rows = db(repo).rawQuery("SELECT step_id,at_ms,origin,label FROM edit_history_steps WHERE edit_id=? ORDER BY seq",
                arrayOf(defaultId)).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    check(cursor.getString(0).isNotBlank() && cursor.getLong(1) > 0 && cursor.getString(2).isNotBlank())
                    check(cursor.getString(3).startsWith("[")); count++
                }
                count
            }
            check(rows == 3)
        }
        SavedPhotoRepository.open(directory).use { repo ->
            val restored = repo.historyPosition(Uri.fromFile(moved), json(0f), "")
            check(restored.editId == defaultId && restored.generation == generation && ev(restored) == 4.0)
            val copy = repo.historyPosition(copyUri, json(0f), "")
            check(copy.canRedo && ev(copy) == 4.0)
        }
    }

    private fun rollbackAndQuota(directory: File) {
        val uri = Uri.parse("content://history-audit/rollback")
        SavedPhotoRepository.open(directory).use { repo ->
            var stable = requireNotNull(repo.save(uri, json(0f), "rollback"))
            stable = requireNotNull(repo.save(uri, json(1f), ""))
            for (point in listOf(EditHistoryRepository.Checkpoint.STEP_WRITTEN, EditHistoryRepository.Checkpoint.EDIT_WRITTEN)) {
                repo.history.checkpoint = { if (it == point) error("rollback-$point") }
                check(runCatching { repo.save(uri, json(2f), "") }.isFailure)
                repo.history.checkpoint = {}
                check(repo.read(uri) == stable.paramsJson)
                check(repo.historyPosition(uri, json(0f), "") == stable)
            }
            for (point in listOf(EditHistoryRepository.Checkpoint.CURSOR_MOVED, EditHistoryRepository.Checkpoint.EDIT_WRITTEN)) {
                repo.history.checkpoint = { if (it == point) error("rollback-$point") }
                check(runCatching { repo.moveHistory(uri, stable.generation, false) }.isFailure)
                repo.history.checkpoint = {}
                check(repo.read(uri) == stable.paramsJson && repo.historyPosition(uri, json(0f), "") == stable)
            }
            val sql = db(repo)
            // max_page_count is connection-local. WAL's pool may run the pragma
            // and writer on different handles, so this isolated quota fixture uses one.
            sql.disableWriteAheadLogging()
            sql.execSQL("VACUUM")
            val maximum = sql.maximumSize
            val pages = sql.rawQuery("PRAGMA page_count", null).use { check(it.moveToFirst()); it.getLong(0) }
            sql.setMaximumSize((pages + 2) * sql.pageSize)
            val random = ByteArray(450_000).also { java.util.Random(937).nextBytes(it) }
            val huge = json(2f, android.util.Base64.encodeToString(random, android.util.Base64.NO_WRAP))
            try {
                val failure = runCatching { repo.save(uri, huge, "") }.exceptionOrNull()
                check(failure is SQLiteFullException) { "Expected real SQLITE_FULL: $failure" }
            } finally { sql.setMaximumSize(maximum) }
            check(repo.read(uri) == stable.paramsJson && repo.historyPosition(uri, json(0f), "") == stable)
            check(sql.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" })
        }
    }

    private fun migrationAndProtection(directory: File) {
        val uri = Uri.parse("content://history-audit/legacy")
        val legacy = "{\"exposure_compensation_ev\":1,\"unknown_before_upgrade\":{\"kept\":true}}"
        var id = ""
        SavedPhotoRepository.open(directory).use { repo ->
            repo.save(uri, legacy, "legacy")
            id = checkNotNull(repo.findByUri(uri)).variantId
            db(repo).execSQL("DROP TABLE edit_history_steps")
            db(repo).execSQL("DROP TABLE edit_history_heads")
            db(repo).version = 5
        }
        SavedPhotoRepository.open(directory).use { repo ->
            check(db(repo).version == 6 && repo.read(uri) == legacy)
            var state = repo.historyPosition(uri, json(0f), "")
            check(state.editId == id && !state.canUndo && state.paramsJson == legacy)
            val expanded = EditorParams.fromJson(JSONObject(legacy)).toJson().toString()
            state = requireNotNull(repo.save(uri, expanded, ""))
            check(!state.canUndo) { "Default expansion invented an edit" }
            state = requireNotNull(repo.save(uri, EditorParams.fromJson(JSONObject(legacy)).apply { exposureEv = 2f }.toJson().toString(), ""))
            state = repo.moveHistory(uri, state.generation, false)
            check(state.paramsJson == legacy && JSONObject(state.paramsJson).getJSONObject("unknown_before_upgrade").getBoolean("kept"))
            val before = repo.read(uri)
            db(repo).execSQL("UPDATE edit_history_heads SET format=99 WHERE edit_id=?", arrayOf(id))
            check(runCatching { repo.save(uri, json(7f), "") }.isFailure)
            check(repo.read(uri) == before)
            db(repo).execSQL("UPDATE edit_history_heads SET format=1 WHERE edit_id=?", arrayOf(id))
            db(repo).execSQL("UPDATE edit_history_steps SET payload=X'000102' WHERE edit_id=? AND seq>?", arrayOf<Any>(id, state.cursor))
            check(runCatching { repo.moveHistory(uri, state.generation, true) }.isFailure)
            check(repo.read(uri) == before && repo.historyPosition(uri, json(0f), "").generation == state.generation)
            db(repo).version = 99
        }
        val file = File(directory, "saved-photos.db")
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        check(runCatching { SavedPhotoRepository.open(directory).close() }.isFailure)
        check(MessageDigest.getInstance("SHA-256").digest(file.readBytes()).contentEquals(hash)) { "Future DB changed" }
    }

    private fun budgets(directory: File) {
        val uri = Uri.parse("content://history-audit/budget")
        SavedPhotoRepository.open(directory).use { repo ->
            var state = requireNotNull(repo.save(uri, json(0f), "budget"))
            repeat(270) { state = requireNotNull(repo.save(uri, json(it + 1f), "")) }
            check(state.retainedStates == 256 && state.earlierStates == 15L)
            val retained = state
            repo.history.checkpoint = { if (it == EditHistoryRepository.Checkpoint.EDIT_WRITTEN) error("trim-rollback") }
            check(runCatching { repo.save(uri, json(271f), "") }.isFailure)
            repo.history.checkpoint = {}
            check(repo.historyPosition(uri, json(0f), "") == retained) { "Trim survived rolled-back edit" }
            var undos = 0
            while (state.canUndo) { state = repo.moveHistory(uri, state.generation, false); undos++ }
            check(undos == 255 && ev(state) == 15.0)
            val largeUri = Uri.parse("content://history-audit/bytes")
            var large = requireNotNull(repo.save(largeUri, json(0f), "bytes"))
            val random = java.util.Random(17)
            repeat(16) { index ->
                val bytes = ByteArray(750_000).also(random::nextBytes)
                large = requireNotNull(repo.save(largeUri, json(index + 1f,
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)), ""))
            }
            check(large.earlierStates > 0 && large.retainedStates < 17)
            val retainedBytes = db(repo).rawQuery("SELECT SUM(length(payload)) FROM edit_history_steps WHERE edit_id=?",
                arrayOf(large.editId)).use { check(it.moveToFirst()); it.getLong(0) }
            check(retainedBytes <= EditHistoryRepository.MAX_HISTORY_BYTES)
        }
    }
}
