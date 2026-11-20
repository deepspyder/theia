package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.exifinterface.media.ExifInterface
import com.spektralab.android.core.model.EditSnapshot
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.ExportFrameRequest
import com.spektralab.android.core.model.ExportOptions
import com.spektralab.android.core.model.ExportSize
import com.spektralab.android.data.export.EphemeralExportFile
import com.spektralab.android.data.export.ExportMetadata
import com.spektralab.android.data.export.ExportPublisher
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.ExportFailure
import com.spektralab.android.engine.ExportFailureCode
import com.spektralab.android.engine.RawDecoder
import com.spektralab.android.engine.host.RenderHostClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import kotlin.random.Random

/** Drives a real encoding worker through cancellation, death and a fresh publication. */
internal class EncodeHostAudit(private val test: Instrumentation) {
    fun run(): String = runBlocking {
        val context = test.targetContext
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory = File(context.cacheDir, "encode-audit-${UUID.randomUUID()}")
        val width = 8192
        val height = 4096
        val request = ExportFrameRequest(UUID.randomUUID().toString(), "0".repeat(64), "fixture.png", false,
            EditSnapshot.capture(EditorParams()), ExportOptions("PNG", 0, "sRGB", 95, true, false, true),
            RawDecoder.decoderIdentity, EngineClient.buildInfo())
        val metadata = ExportMetadata(mapOf("Make" to "TheiaAudit"))
        val destination = File(directory, "recovered.png")
        try {
            EphemeralExportFile.create(context).use { pixels ->
                ParcelFileDescriptor.AutoCloseOutputStream(pixels.writer).buffered().use { stream ->
                    val row = ByteArray(width * 8)
                    val random = Random(413)
                    repeat(height) {
                        random.nextBytes(row)
                        stream.write(row)
                    }
                }
                val encodingStarted = java.util.concurrent.atomic.AtomicBoolean()
                val hostStartedAt = java.util.concurrent.atomic.AtomicLong()
                var workerPid = 0
                val cancelled = runCatching {
                    RenderHostClient(context).apply { onHostStarted = { workerPid = it } }
                        .encode(request, pixels.reader, width, height, 0, metadata,
                            check = {
                                val startAt = hostStartedAt.get()
                                if (encodingStarted.get() && startAt > 0 &&
                                    android.os.SystemClock.elapsedRealtime() - startAt >= 300)
                                    throw CancellationException("Cancelled while encoding PNG rows")
                            },
                            progress = { stage, _ ->
                                if (stage == "encoding") {
                                    hostStartedAt.compareAndSet(0, android.os.SystemClock.elapsedRealtime())
                                    encodingStarted.set(true)
                                }
                            }).close()
                }.exceptionOrNull()
                check(encodingStarted.get() && workerPid > 0 && workerPid != Process.myPid() &&
                    cancelled?.message.orEmpty().contains("cancel", true)) {
                    "Cancellation did not interrupt PNG row encoding"
                }

                var killedWorkerPid = 0
                var killedDuringEncode = false
                val client = RenderHostClient(context).apply { onHostStarted = { killedWorkerPid = it } }
                val failed = runCatching {
                    client.encode(request, pixels.reader, width, height, 0, metadata, {}, progress = { stage, _ ->
                        if (stage == "encoding" && !killedDuringEncode) {
                            check(killedWorkerPid > 0 && killedWorkerPid != Process.myPid())
                            Thread.sleep(500)
                            killedDuringEncode = true
                            Process.killProcess(killedWorkerPid)
                        }
                    }).close()
                }.exceptionOrNull()
                check(killedDuringEncode && (failed as? ExportFailure)?.code == ExportFailureCode.HOST_DIED) {
                    "Worker death did not fail the encode: $failed"
                }

                var recoveredPid = 0
                val next = RenderHostClient(context).apply { onHostStarted = { recoveredPid = it } }
                val resized = request.copy(options = request.options.copy(customSize = ExportSize(ExportSize.Kind.LONG_EDGE, 600.0)))
                next.encode(resized, pixels.reader, width, height, 0, metadata, {}).use { encoded ->
                    check(recoveredPid > 0 && recoveredPid != killedWorkerPid && recoveredPid != Process.myPid())
                    check(encoded.width == 600 && encoded.height == 300)
                    val interrupted = runCatching {
                        ExportPublisher(context, {}, {}).writeFile(directory, destination.name) { stream ->
                            var checks = 0
                            encoded.copyTo(stream) {
                                if (++checks == 2) throw CancellationException("Cancelled during publication copy")
                            }
                        }
                    }.exceptionOrNull()
                    check(interrupted is CancellationException && directory.list().orEmpty().isEmpty())
                    ExportPublisher(context, {}, {}).writeFile(directory, destination.name) { stream ->
                        encoded.copyTo(stream) {}
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(destination.path, bounds)
                    check(bounds.outWidth == encoded.width && bounds.outHeight == encoded.height)
                    check(ExifInterface(destination).getAttribute(ExifInterface.TAG_MAKE) == "TheiaAudit")
                }
            }
            "Timed cancellation after PNG row encoding starts and worker death during encoding, new worker recovery, aborted publication cleanup, 600x300 output and EXIF passed"
        } finally {
            destination.delete()
            directory.delete()
            test.runOnMainSync { activity.finish() }
        }
    }
}
