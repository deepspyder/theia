package com.spektralab.android.feature.settings

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spektralab.android.R
import com.spektralab.android.core.model.AppSettings
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.cache.RenderDiskCache
import com.spektralab.android.data.diagnostics.DiagnosticReport
import com.spektralab.android.data.local.AppSettingsStore
import com.spektralab.android.engine.Stock
import com.spektralab.android.engine.StockCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    private val store = AppSettingsStore.get(app)
    private val cache = ExportRenderCache.get(app)
    var value by mutableStateOf(AppSettings()); private set
    var unavailable by mutableStateOf(false); private set
    var busy by mutableStateOf(true); private set
    var exporting by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var storage by mutableStateOf<RenderDiskCache.Snapshot?>(null); private set
    var films by mutableStateOf<List<Stock>>(emptyList()); private set
    var papers by mutableStateOf<List<Stock>>(emptyList()); private set
    private var operation: Job? = null

    init { refresh(initial = true) }

    fun dismissMessage() { message = null }

    fun refresh(initial: Boolean = false) {
        if (busy && !initial) return
        run(R.string.settings_read_failed) {
            val loaded = withContext(Dispatchers.IO) {
                val read = store.read()
                val catalog = StockCatalog.load(app)
                cache.setBudget(read.value.renderCacheBytes)
                Triple(read, catalog, cache.storage())
            }
            value = loaded.first.value; unavailable = loaded.first.unavailable
            films = loaded.second.first; papers = loaded.second.second; storage = loaded.third
        }
    }

    fun update(next: AppSettings) {
        if (busy || unavailable || next == value) return
        if (films.none { it.id == next.defaultFilm } || papers.none { it.id == next.defaultPaper } ||
            !StockCatalog.canPrint(next.defaultFilm,next.defaultPaper)) {
            message = app.getString(R.string.settings_material_invalid); return
        }
        run(R.string.settings_save_failed) {
            withContext(Dispatchers.IO) { store.save(next) }
            value = next
            try {
                storage = withContext(Dispatchers.IO) { cache.setBudget(next.renderCacheBytes); cache.storage() }
                message = app.getString(R.string.settings_saved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = app.getString(R.string.settings_saved_cache_deferred) }
        }
    }

    fun chooseFilm(id: String) {
        val film = films.firstOrNull { it.id == id } ?: return
        val compatible = papers.filter { StockCatalog.canPrint(id,it.id) }
        val paper = compatible.firstOrNull { it.id == film.targetPrint } ?: compatible.firstOrNull() ?: return
        update(value.copy(defaultFilm = id, defaultPaper = paper.id))
    }

    fun clearCache() {
        if (busy) return
        run(R.string.settings_cache_failed) {
            val result = withContext(Dispatchers.IO) {
                val task = currentCoroutineContext()
                cache.clearDisposable { task.ensureActive() }
            }
            storage = result.retained
            message = app.getString(if (result.failedFiles == 0) R.string.settings_cache_cleared else R.string.settings_cache_partial,
                android.text.format.Formatter.formatShortFileSize(app,result.freedBytes))
        }
    }

    fun export(uri: Uri?, workspace: DiagnosticReport.Workspace) {
        if (uri == null) return
        require(uri.scheme == "content")
        if (busy) {
            val pending = operation
            viewModelScope.launch { pending?.join(); export(uri,workspace) }
            return
        }
        exporting = true
        run(R.string.settings_export_failed) {
            try {
                withContext(Dispatchers.IO) {
                    val task = currentCoroutineContext()
                    val settings = value
                    val bytes = DiagnosticReport.create(app,settings,cache.storage(),cache.stats,workspace,unavailable) { task.ensureActive() }
                    task.ensureActive()
                    val stream = requireNotNull(app.contentResolver.openOutputStream(uri,"wt"))
                    stream.use {
                        for (offset in bytes.indices step 8192) {
                            task.ensureActive(); it.write(bytes,offset,minOf(8192,bytes.size-offset))
                        }
                        it.flush(); task.ensureActive()
                    }
                }
                message = app.getString(R.string.settings_export_saved)
            } catch (failure: Throwable) {
                // ACTION_CREATE_DOCUMENT supplied this new output. Never delete an arbitrary URI or a source.
                if (DocumentsContract.isDocumentUri(app,uri)) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(app.contentResolver,uri) }
                }
                throw failure
            }
        }
    }

    fun cancel() { operation?.cancel() }

    private fun run(failureMessage: Int, action: suspend () -> Unit) {
        busy = true
        operation = viewModelScope.launch {
            try { action() }
            catch (_: CancellationException) { message = app.getString(R.string.settings_cancelled) }
            catch (_: Exception) { message = app.getString(failureMessage) }
            catch (_: OutOfMemoryError) { message = app.getString(failureMessage) }
            finally { busy = false; exporting = false }
        }
    }
}
