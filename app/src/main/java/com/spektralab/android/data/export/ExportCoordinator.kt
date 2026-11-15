package com.spektralab.android.data.export

import android.content.Context
import android.net.Uri
import com.spektralab.android.core.model.*
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.RawDecoder
import com.spektralab.android.engine.host.DescriptorRgba16Source
import com.spektralab.android.engine.host.RenderHostClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** One process-wide export coordinator; the caller retains its existing interactive task transaction. */
internal class ExportCoordinator private constructor(context: Context) {
    private val app = context.applicationContext
    private val store = ExportJobStore(app)
    private val gate = Mutex()
    private var recovered = false

    inner class Task internal constructor(val request: ExportFrameRequest, private val verify: () -> Unit) {
        private var state = ExportJobStore.State.QUEUED
        private var destination: String? = null

        fun phase(next: ExportJobStore.State) {
            check(state !in setOf(ExportJobStore.State.SUCCEEDED, ExportJobStore.State.FAILED, ExportJobStore.State.CANCELLED))
            check(next.ordinal >= state.ordinal)
            state = next; store.write(request, next, destination)
        }

        fun publishing(value: String) { destination = value; phase(ExportJobStore.State.PUBLISHING) }
        fun succeeded(value: String) { destination = value; phase(ExportJobStore.State.SUCCEEDED) }
        fun verifySource() = verify()

        suspend fun render(source: ExportSourceSnapshot, check: () -> Unit,
                           progress: (String, Float) -> Unit): DescriptorRgba16Source {
            phase(ExportJobStore.State.RENDERING)
            return RenderHostClient(app).render(request, source.descriptor, check, progress)
        }

        internal fun failed(failure: Throwable) {
            if (state == ExportJobStore.State.SUCCEEDED) return
            val cancelled = failure is kotlinx.coroutines.CancellationException || failure.message.orEmpty().contains("cancel", true)
            store.write(request, if (cancelled) ExportJobStore.State.CANCELLED else ExportJobStore.State.FAILED, destination,
                "${failure.javaClass.simpleName}: ${failure.message}")
        }
    }

    suspend fun <T> run(uri: Uri, name: String, edit: EditSnapshot, options: ExportOptions,
                        check: () -> Unit, block: suspend (Task, ExportSourceSnapshot) -> T): T = gate.withLock {
        withContext(Dispatchers.IO) { if (!recovered) { store.recover(); recovered = true } }
        check()
        var staged: ExportSourceSnapshot? = null
        try {
            withContext(Dispatchers.IO) { staged = measureExport("source_snapshot") { ExportSourceSnapshot.create(app, uri, options.keepLocation, check) } }
            val source = requireNotNull(staged)
            val request = ExportFrameRequest(UUID.randomUUID().toString(), source.sha256, name.take(512),
                RawDecoder.isRaw(app, uri, name), edit, options, RawDecoder.decoderIdentity, EngineClient.buildInfo())
            val task = Task(request) { source.verifyOriginal(app, uri, options.keepLocation, check) }
            withContext(Dispatchers.IO) { store.write(request, ExportJobStore.State.QUEUED); task.phase(ExportJobStore.State.PREPARING) }
            try { block(task, source) }
            catch (failure: Throwable) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { task.failed(failure) }
                throw failure
            }
        } finally { staged?.close() }
    }

    companion object {
        @Volatile private var instance: ExportCoordinator? = null
        fun get(context: Context): ExportCoordinator = instance ?: synchronized(this) {
            instance ?: ExportCoordinator(context).also { instance = it }
        }
    }
}
