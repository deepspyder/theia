package com.spektralab.android.engine.host

import android.os.ParcelFileDescriptor
import com.spektralab.android.engine.Rgba16Source
import java.io.Closeable
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Row-addressable exact U16 pixels. Binder carries only this descriptor and dimensions. */
internal class DescriptorRgba16Source(
    descriptor: ParcelFileDescriptor,
    override val width: Int,
    override val height: Int,
) : Rgba16Source, Closeable {
    private val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    private val channel = stream.channel
    private val buffer: ByteBuffer

    init {
        try {
            require(width in 1..131072 && height in 1..131072)
            require(channel.size() == width.toLong() * height * 8) { "Incomplete host pixels" }
            buffer = ByteBuffer.allocate(width * minOf(height, 32) * 8).order(ByteOrder.LITTLE_ENDIAN)
        } catch (failure: Throwable) { stream.close(); throw failure }
    }

    @Synchronized
    override fun readRows(y: Int, rows: Int, destination: ShortArray) {
        require(y >= 0 && rows > 0 && y.toLong() + rows <= height && destination.size.toLong() >= width.toLong() * rows * 4)
        var row = 0
        while (row < rows) {
            val count = minOf(32, rows - row)
            buffer.clear(); buffer.limit(width * count * 8)
            var position = (y + row).toLong() * width * 8
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                if (read <= 0) throw EOFException("Incomplete host pixel rows")
                position += read
            }
            buffer.flip(); buffer.asShortBuffer().get(destination, row * width * 4, count * width * 4)
            row += count
        }
    }

    override fun close() = stream.close()
}
