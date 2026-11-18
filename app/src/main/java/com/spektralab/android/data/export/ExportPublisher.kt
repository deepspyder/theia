package com.spektralab.android.data.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import com.spektralab.android.core.model.ExportNaming

/** Encode to an unpublished destination, commit once, and remove incomplete files on failure. */
internal class ExportPublisher(
    private val context: Context,
    private val checkCancelled: () -> Unit,
    private val onCommitted: () -> Unit,
) {
    internal var onPrepared: (String) -> Unit = {}
    internal var onReadyToCommit: () -> Unit = {}
    fun write(name: String, mime: String, encode: (OutputStream) -> Unit): String = synchronized(publicationLock) {
        require(name == File(name).name && name.isNotBlank() && name.none(Char::isISOControl)) { "Invalid output name" }
        writeLocked(name, mime, encode)
    }

    private fun writeLocked(name: String, mime: String, encode: (OutputStream) -> Unit): String {
        checkCancelled()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, uniqueName(name) { candidate ->
                    context.contentResolver.query(
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        arrayOf(MediaStore.Images.Media._ID),
                        "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?",
                        arrayOf(candidate, "${Environment.DIRECTORY_PICTURES}/Theia/"), null,
                    )?.use { it.moveToFirst() } ?: error("Cannot check output name")
                })
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Theia")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            resolver.insert(collection, values)?.let { uri ->
                try {
                    onPrepared(uri.toString())
                    val stream = resolver.openOutputStream(uri) ?: error("无法写入相册文件")
                    stream.use(encode)
                    checkCancelled()
                    onReadyToCommit()
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    check(resolver.update(uri, values, null, null) > 0) { "无法完成相册文件" }
                    onCommitted()
                    return "相册: $uri"
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                    throw error
                }
            }
        }

        val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        return writeFile(directory, name, encode)
    }

    internal fun writeFile(directory: File, name: String, encode: (OutputStream) -> Unit): String = synchronized(publicationLock) {
        checkCancelled()
        directory.mkdirs()
        val destination = File(directory, uniqueName(name) {
            File(directory, it).exists() || File(directory, "$it.pending").exists()
        })
        val pending = File(directory, "${destination.name}.pending")
        check(pending.createNewFile()) { "Output name already reserved" }
        try {
            onPrepared(pending.absolutePath)
            pending.outputStream().use(encode)
            checkCancelled()
            onReadyToCommit()
            // Both paths are private app-owned siblings. No REPLACE_EXISTING: a
            // destination created since reservation must never be overwritten.
            java.nio.file.Files.move(pending.toPath(), destination.toPath())
            onCommitted()
            destination.absolutePath
        } finally {
            pending.delete()
        }
    }

    companion object {
        private val publicationLock = Any()
        internal fun uniqueName(name: String, exists: (String) -> Boolean): String {
            val extension = name.substringAfterLast('.', "")
            require(extension in listOf("jpg", "jpeg", "png", "tiff", "tif", "heic")) { "Invalid output extension" }
            val stem = ExportNaming.safePart(name.substringBeforeLast('.'))
            for (index in 0..9999) {
                val candidate = "$stem${if (index == 0) "" else " ($index)"}.$extension"
                if (!exists(candidate)) return candidate
            }
            error("Too many outputs with the same name")
        }
    }
}
