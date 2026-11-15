package com.spektralab.android.data.export

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.spektralab.android.core.model.EditSnapshot
import com.spektralab.android.core.model.ExportOptions
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.cache.RenderDiskCache
import com.spektralab.android.engine.Rgba16Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared single/batch output path; the feature owns only preview hand-off and presentation. */
internal class PhotoExportPipeline(private val context: Context) {
    data class Output(val path: String, val width: Int, val height: Int)

    suspend fun write(uri: Uri, name: String, edit: EditSnapshot, options: ExportOptions,
                      check: () -> Unit, beforeNative: suspend () -> Unit,
                      progress: (String, Float) -> Unit,
                      encode: (Rgba16Source, ExportMetadata, ExportCoordinator.Task) -> String): Output =
        ExportCoordinator.get(context).run(uri, name, edit, options, check) { task, source ->
            val metadata = withContext(Dispatchers.IO) {
                val exif = runCatching { ExifInterface(source.descriptor.fileDescriptor) }.getOrNull()
                ExportMetadata.from(exif, options.keepCapture, options.keepLocation)
            }
            val cache = ExportRenderCache.get(context)
            val request = withContext(Dispatchers.IO) {
                cache.prepareResolved(uri, task.request.edit.toParams(), options, task.request.realisation, check)?.takeIf { it.sourceHash == source.sha256 }
            }
            var lease: RenderDiskCache.Lease? = null
            try {
                withContext(Dispatchers.IO) { lease = request?.let { cache.acquire(it, check) } }
                suspend fun save(result: Rgba16Source): Output {
                    check(); progress("encoding", 96f)
                    return withContext(Dispatchers.IO) {
                        task.phase(ExportJobStore.State.ENCODING)
                        val path = encode(result, metadata, task)
                        task.succeeded(path)
                        Output(path, result.width, result.height)
                    }
                }
                val cached = lease
                if (cached != null) save(cached)
                else {
                    beforeNative(); check()
                    task.render(source, check, progress).use { result ->
                        if (request != null) withContext(Dispatchers.IO) { cache.store(request, uri, result, check) }
                        save(result)
                    }
                }
            } finally { lease?.close() }
        }
}
