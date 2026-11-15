package com.spektralab.android.engine.host

import android.content.*
import android.os.*
import com.spektralab.android.core.model.ExportFrameRequest
import com.spektralab.android.engine.SpkException
import com.spektralab.android.engine.ExportFailure
import com.spektralab.android.engine.ExportFailureCode
import kotlinx.coroutines.*
import java.io.Closeable

/** Asynchronous control channel. Progress/cancel never queue behind a native dispatch. */
internal class RenderHostClient(private val context: Context) {
    internal var onHostStarted: ((Int) -> Unit)? = null

    suspend fun render(request: ExportFrameRequest, source: ParcelFileDescriptor,
                       check: () -> Unit, progress: (String, Float) -> Unit): DescriptorRgba16Source = coroutineScope {
        val connection = Connection(request.jobId, progress)
        var transferred = false
        var watcher: Job? = null
        try {
            withContext(Dispatchers.Main.immediate) { connection.bind() }
            val host = withTimeout(10_000) { connection.ready.await() }
            check()
            val json = request.toJson()
            require(json.toByteArray(Charsets.UTF_8).size <= 128 * 1024)
            host.send(Message.obtain(null, RenderHostProtocol.START).apply {
                replyTo = connection.receiver
                data = Bundle().apply {
                    putString(RenderHostProtocol.JOB, request.jobId)
                    putString(RenderHostProtocol.REQUEST, json)
                    putParcelable(RenderHostProtocol.DESCRIPTOR, source)
                }
            })
            watcher = launch(Dispatchers.IO) {
                while (isActive && !connection.result.isCompleted) {
                    delay(100)
                    try { check() } catch (failure: Exception) {
                        connection.result.completeExceptionally(failure)
                        break
                    }
                }
            }
            val result = connection.result.await()
            check()
            transferred = true
            result
        } finally {
            withContext(NonCancellable) {
                watcher?.cancelAndJoin()
                connection.finish(transferred)
            }
        }
    }

    private inner class Connection(val id: String, val progress: (String, Float) -> Unit) : ServiceConnection {
        val ready = CompletableDeferred<Messenger>()
        val result = CompletableDeferred<DescriptorRgba16Source>()
        private val terminated = CompletableDeferred<Unit>()
        private var host: Messenger? = null
        private var binder: IBinder? = null
        private var bound = false
        private var pid = 0
        private var pixels: DescriptorRgba16Source? = null
        private val intent = Intent(context, RenderHostService::class.java)
        private val death = IBinder.DeathRecipient { failed("Render host exited; the current edits are preserved") }
        val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
            message.data.classLoader = ParcelFileDescriptor::class.java.classLoader
            @Suppress("DEPRECATION")
            val descriptor = message.data.getParcelable<ParcelFileDescriptor>(RenderHostProtocol.DESCRIPTOR)
            if (message.data.getString(RenderHostProtocol.JOB) != id) { descriptor?.close(); return@Handler true }
            when (message.what) {
                RenderHostProtocol.STARTED -> {
                    pid = message.data.getInt("pid")
                    onHostStarted?.invoke(pid)
                }
                RenderHostProtocol.PROGRESS -> if (!result.isCompleted) runCatching {
                    progress(message.data.getString("stage").orEmpty(), message.data.getFloat("percent"))
                }.onFailure { result.completeExceptionally(it) }
                RenderHostProtocol.COMPLETE -> {
                    terminated.complete(Unit)
                    if (descriptor != null) {
                        try {
                            val output = DescriptorRgba16Source(descriptor, message.data.getInt("width"), message.data.getInt("height"))
                            if (result.complete(output)) pixels = output else output.close()
                        } catch (failure: Exception) { result.completeExceptionally(failure) }
                    } else result.completeExceptionally(SpkException("Missing host result"))
                }
                RenderHostProtocol.FAILED -> {
                    descriptor?.close(); terminated.complete(Unit)
                    val error = message.data.getString("error") ?: "Render host failed"
                    val code = runCatching { ExportFailureCode.valueOf(message.data.getString("code").orEmpty()) }
                        .getOrDefault(ExportFailureCode.INTERNAL)
                    result.completeExceptionally(ExportFailure(code, error))
                }
                RenderHostProtocol.ABORTING -> {
                    descriptor?.close()
                    // Cleanup still waits for termination or kills a stuck worker before GPU hand-back.
                    result.completeExceptionally(ExportFailure(ExportFailureCode.TIMEOUT,
                        message.data.getString("error") ?: "Render host timed out"))
                }
                else -> descriptor?.close()
            }
            true
        })

        fun bind() {
            RenderHostLease.checkHealthy()
            if (!RenderHostLease.active) context.startForegroundService(intent)
            bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE)
            check(bound) { "Cannot bind render host" }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            try {
                service.linkToDeath(death, 0)
                val messenger = Messenger(service); host = messenger; ready.complete(messenger)
            } catch (_: RemoteException) { failed("Render host connection failed") }
        }

        override fun onServiceDisconnected(name: ComponentName) = failed("Render host disconnected")
        override fun onBindingDied(name: ComponentName) = failed("Render host binding died")
        override fun onNullBinding(name: ComponentName) = failed("Render host unavailable")

        private fun failed(message: String) {
            val error = ExportFailure(ExportFailureCode.HOST_DIED, message)
            ready.completeExceptionally(error); result.completeExceptionally(error); terminated.complete(Unit)
        }

        suspend fun finish(transferred: Boolean) {
            if (!transferred) result.cancel() // Late replies must close their FD rather than acquire an abandoned result.
            if (!terminated.isCompleted) {
                runCatching { host?.send(Message.obtain(null, RenderHostProtocol.CANCEL).apply {
                    data = Bundle().apply { putString(RenderHostProtocol.JOB, id) }
                }) }
                if (withTimeoutOrNull(8_000) { terminated.await(); true } != true && pid > 0 && pid != Process.myPid()) {
                    // Same-UID private service, known from this connection. End a stuck worker
                    // before the UI is allowed to recreate its GPU owner.
                    Process.killProcess(pid)
                    withTimeoutOrNull(3_000) { terminated.await() }
                }
            }
            if (!transferred) pixels?.close()
            withContext(Dispatchers.Main.immediate) {
                binder?.let { runCatching { it.unlinkToDeath(death, 0) } }
                if (bound) { context.unbindService(this@Connection); bound = false }
                if (!RenderHostLease.active) context.stopService(intent)
            }
        }
    }
}
