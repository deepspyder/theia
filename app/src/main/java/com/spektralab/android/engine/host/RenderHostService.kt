package com.spektralab.android.engine.host

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import com.spektralab.android.R
import com.spektralab.android.core.model.ExportArtifact
import com.spektralab.android.core.model.ExportFrameRequest
import com.spektralab.android.data.export.EphemeralExportFile
import com.spektralab.android.data.export.ExportMetadata
import com.spektralab.android.data.export.FinishedPhoto
import org.json.JSONObject
import com.spektralab.android.data.export.ExportSourceDecoder
import com.spektralab.android.data.export.measureExport
import com.spektralab.android.engine.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** One export owner in :render. No pixel arrays, Bitmap or native pointers are sent through Binder. */
class RenderHostService : Service() {
    private class Work(val id: String, val reply: Messenger) {
        val cancelled = AtomicBoolean()
        @Volatile var engine: EngineClient? = null
        @Volatile var stage: String = "preparing"
    }

    private val owner = NativeSessionOwner.executor
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active: Work? = null
    private var bound = false
    private var cpuLock: PowerManager.WakeLock? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            RenderHostProtocol.START, RenderHostProtocol.ENCODE -> start(message)
            RenderHostProtocol.CANCEL -> active?.takeIf { it.id == message.data.getString(RenderHostProtocol.JOB) }?.let {
                it.cancelled.set(true); it.engine?.cancel()
            }
            RenderHostProtocol.RELEASE_DEVICE -> message.replyTo?.let { reply ->
                val id = message.data.getString(RenderHostProtocol.JOB).orEmpty()
                send(reply, RenderHostProtocol.DEVICE_RELEASING, id) { putInt("pid", Process.myPid()) }
                owner.execute {
                    val failure = runCatching { NativeSessionOwner.releaseDevice() }.exceptionOrNull()
                    send(reply, RenderHostProtocol.DEVICE_RELEASED, id) {
                        if (failure != null) putString("error", failure.message ?: "Device release failed")
                    }
                }
            }
        }
        true
    })

    override fun onBind(intent: Intent): IBinder { bound = true; return messenger.binder }

    override fun onUnbind(intent: Intent): Boolean {
        bound = false
        active?.let { it.cancelled.set(true); it.engine?.cancel() }
        releaseCpu()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (cpuLock == null) cpuLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Theia:Export").apply { setReferenceCounted(false) }
        cpuLock?.acquire(60 * 60 * 1000L)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.export_host_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle(getString(R.string.export_host_title)).setContentText(getString(R.string.export_host_working))
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent().setClassName(packageName, "$packageName.MainActivity").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true).build()
        if (Build.VERSION.SDK_INT >= 35) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION, notification)
        return START_NOT_STICKY
    }

    private fun start(message: Message) {
        cpuLock?.acquire(60 * 60 * 1000L)
        message.data.classLoader = ParcelFileDescriptor::class.java.classLoader
        @Suppress("DEPRECATION")
        val source = message.data.getParcelable<ParcelFileDescriptor>(RenderHostProtocol.DESCRIPTOR) ?: return
        val reply = message.replyTo ?: run { source.close(); return }
        val id = message.data.getString(RenderHostProtocol.JOB).orEmpty()
        if (active != null) {
            source.close(); send(reply, RenderHostProtocol.FAILED, id) { putString("error", "Render host busy") }; return
        }
        val work = Work(id, reply)
        val requestJson = message.data.getString(RenderHostProtocol.REQUEST).orEmpty()
        active = work
        send(reply, RenderHostProtocol.STARTED, id) { putInt("pid", Process.myPid()) }
        main.post(object : Runnable {
            override fun run() {
                if (active !== work) return
                val progress = if (work.stage == "rendering") runCatching { work.engine?.progressSnapshot() }.getOrNull() else null
                if (!send(reply, RenderHostProtocol.PROGRESS, id) {
                    putString("stage", work.stage); putFloat("percent", progress?.percent ?: 0f)
                }) { work.cancelled.set(true); work.engine?.cancel() }
                main.postDelayed(this, 150)
            }
        })
        val encoding = message.what == RenderHostProtocol.ENCODE
        val arguments = message.data
        owner.execute {
            execute(work, requestJson, source, arguments.takeIf { encoding })
        }
    }

    private fun execute(work: Work, requestJson: String, source: ParcelFileDescriptor, encoding: Bundle?) {
        fun checkCancelled() { if (work.cancelled.get() || Thread.currentThread().isInterrupted) throw SpkException("render cancelled") }
        try {
            source.use {
                if (encoding != null) {
                    encode(work, requestJson, source, encoding, ::checkCancelled)
                    return
                }
                // Object singletons are process-local. Resolve positive/BW material semantics
                // before converting the frozen edit to native parameters in this :render process.
                measureExport("material_catalog") { StockCatalog.load(this) }
                val request = ExportFrameRequest.fromJson(requestJson)
                require(request.jobId == work.id)
                require(request.decoderIdentity == RawDecoder.decoderIdentity && request.rendererIdentity == EngineClient.buildInfo()) { "Renderer identity changed" }
                val hash = MessageDigest.getInstance("SHA-256")
                // A fresh open has an independent position; never seek the Binder-shared descriptor.
                val path = File("/proc/self/fd/${source.fd}")
                path.inputStream().use { input ->
                    val bytes = ByteArray(128 * 1024)
                    while (true) {
                        checkCancelled(); val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count)
                    }
                }
                require(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == request.sourceHash) { "Export source changed" }
                checkCancelled()
                val output = EphemeralExportFile.create(this)
                try {
                    var width = 0; var height = 0
                    NativeSessionOwner.use(this) { engine ->
                        work.engine = engine
                        stage(work, "decoding")
                        // Same frozen native request, small enough to avoid the
                        // large-frame driver path. Never used as export pixels.
                        val reference = if (request.artifact==com.spektralab.android.core.model.ExportArtifact.FINISHED && request.raw && request.options.renderResolution == 0) {
                            ExportSourceDecoder.open(this, engine, Uri.fromFile(path), true,
                                request.edit.toParams(), request.options.copy(resolution = 640, customSize = null), ::checkCancelled)
                            engine.renderExportResolved(request.realisation.film, request.realisation.print).use {
                                RenderAppearance.capture(it, ::checkCancelled)
                            }
                        } else null
                        ExportSourceDecoder.open(this, engine, Uri.fromFile(path), request.raw, request.edit.toParams(), request.options, ::checkCancelled)
                        checkCancelled(); stage(work, "rendering")
                        measureExport("host_render") {
                            if(request.artifact==com.spektralab.android.core.model.ExportArtifact.DIGITAL_INTERMEDIATE)
                                engine.renderDigitalIntermediateResolved(request.realisation.film,request.realisation.print)
                            else engine.renderExportResolved(request.realisation.film, request.realisation.print)
                        }.use { result ->
                            if (reference != null && !RenderAppearance.compatible(reference,
                                    RenderAppearance.capture(result, ::checkCancelled))) {
                                throw ExportFailure(ExportFailureCode.INTERNAL, getString(R.string.export_corrupt_frame))
                            }
                            stage(work, "readback")
                            width = result.width; height = result.height
                            val size = width.toLong() * height * 8
                            check(size < cacheDir.usableSpace - 64L * 1024 * 1024) { "Insufficient export result space" }
                            ParcelFileDescriptor.AutoCloseOutputStream(output.writer).use { destination ->
                                val rows = minOf(32, height)
                                val strip = ShortArray(width * rows * 4)
                                val bytes = ByteBuffer.allocate(width * rows * 8).order(ByteOrder.LITTLE_ENDIAN)
                                var y = 0
                                while (y < height) {
                                    checkCancelled(); val count = minOf(rows, height - y)
                                    result.readRows(y, count, strip)
                                    bytes.clear(); bytes.asShortBuffer().put(strip, 0, width * count * 4)
                                    destination.write(bytes.array(), 0, width * count * 8); y += count
                                }
                            }
                        }
                        stage(work, "releasing")
                        android.util.Log.i("SpkExport", "host_memory ${engine.memoryReport()}")
                    }
                    work.engine = null
                    checkCancelled()
                    terminal(work, RenderHostProtocol.COMPLETE) {
                        putInt("width", width); putInt("height", height)
                        putParcelable(RenderHostProtocol.DESCRIPTOR, output.reader)
                    }
                } finally { output.close() }
            }
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is OutOfMemoryError) throw failure
            terminal(work, RenderHostProtocol.FAILED) {
                val code = when {
                    work.cancelled.get() || failure.message.orEmpty().contains("cancel", true) -> ExportFailureCode.CANCELLED
                    failure is ExportFailure -> failure.code
                    EngineFailure.isDeviceLost(failure) -> ExportFailureCode.DEVICE_LOST
                    failure is OutOfMemoryError -> ExportFailureCode.MEMORY
                    failure is java.io.IOException || failure.message.orEmpty().contains("space", true) -> ExportFailureCode.STORAGE
                    failure is IllegalArgumentException -> ExportFailureCode.REQUEST
                    else -> ExportFailureCode.INTERNAL
                }
                putString("code", code.name)
                putString("error", failure.message ?: failure.javaClass.simpleName)
                putBoolean("cancelled", work.cancelled.get() || failure.message.orEmpty().contains("cancel", true))
                putBoolean("device_lost", EngineFailure.isDeviceLost(failure))
            }
        } finally {
            work.engine = null
            main.post {
                if (active === work) active = null
                if (!bound && active == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun encode(work: Work, requestJson: String, source: ParcelFileDescriptor,
                       arguments: Bundle, check: () -> Unit) {
        val request = ExportFrameRequest.fromJson(requestJson)
        require(request.jobId == work.id && request.artifact == ExportArtifact.FINISHED)
        val json = JSONObject(arguments.getString(RenderHostProtocol.METADATA).orEmpty())
        val metadata = ExportMetadata(json.keys().asSequence().associateWith(json::getString))
        val width = arguments.getInt(RenderHostProtocol.WIDTH)
        val height = arguments.getInt(RenderHostProtocol.HEIGHT)
        val offset = arguments.getLong(RenderHostProtocol.OFFSET)
        check()
        stage(work, "encoding")
        DescriptorRgba16Source(source.dup(), width, height, offset).use { pixels ->
            val finished = FinishedPhoto.prepare(pixels, request, check)
            EphemeralExportFile.create(this).use { output ->
                measureExport("host_encode_${request.options.format}") {
                    ParcelFileDescriptor.AutoCloseOutputStream(output.writer).use {
                        FinishedPhoto.write(this, finished, request, metadata, it, check)
                    }
                }
                check()
                terminal(work, RenderHostProtocol.COMPLETE) {
                    putInt(RenderHostProtocol.WIDTH, finished.width)
                    putInt(RenderHostProtocol.HEIGHT, finished.height)
                    putParcelable(RenderHostProtocol.DESCRIPTOR, output.reader)
                }
            }
        }
    }

    private fun terminal(work: Work, type: Int, fill: Bundle.() -> Unit) {
        // A client can submit the next stage as soon as it receives this reply.
        if (active === work) active = null
        send(work.reply, type, work.id, fill)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        active?.let {
            it.cancelled.set(true); it.engine?.cancel()
            send(it.reply, RenderHostProtocol.ABORTING, it.id) { putString("error", "Render host time limit reached") }
        }
        releaseCpu()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
    }

    override fun onDestroy() {
        active?.let { it.cancelled.set(true); it.engine?.cancel() }
        releaseCpu()
        main.removeCallbacksAndMessages(null); super.onDestroy()
    }

    private fun send(reply: Messenger, type: Int, id: String, fill: Bundle.() -> Unit = {}): Boolean = runCatching {
        reply.send(Message.obtain(null, type).apply { data = Bundle().apply { putString(RenderHostProtocol.JOB, id); fill() } })
    }.isSuccess

    private fun stage(work: Work, name: String) {
        work.stage = name
        if (!send(work.reply, RenderHostProtocol.PROGRESS, work.id) { putString("stage", name); putFloat("percent", 0f) }) {
            work.cancelled.set(true)
        }
    }

    private fun releaseCpu() { cpuLock?.let { if (it.isHeld) it.release() }; cpuLock = null }

    private companion object { const val CHANNEL = "theia-export"; const val NOTIFICATION = 106 }
}
