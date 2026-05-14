package be.mygod.librootkotlinx.net

import android.net.LocalSocket
import android.os.Handler
import be.mygod.librootkotlinx.io.FileDescriptorEventAwaiter
import be.mygod.librootkotlinx.io.FileDescriptorReadChannel
import be.mygod.librootkotlinx.io.FileDescriptorWriteChannel
import be.mygod.librootkotlinx.io.isNonblocking
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CancellationException
import java.io.Closeable

/**
 * Async wrapper for [LocalSocket].
 */
class ALocalSocket(val socket: LocalSocket, private val handler: Handler) : Closeable {
    private val eventAwaiter = FileDescriptorEventAwaiter(socket.fileDescriptor, handler.looper.queue)
    private val lock = Any()
    private var closed = false
    private var readChannel: ByteReadChannel? = null
    private var writeChannel: ByteWriteChannel? = null

    init {
        socket.fileDescriptor.isNonblocking = true
    }

    /**
     * Opens the read half of this socket as a [ByteReadChannel].
     *
     * Closing or cancelling the returned channel shuts down this socket's input half. Use [close] to close the whole
     * socket.
     */
    fun openReadChannel(buffer: ByteArray = ByteArray(DEFAULT_BUFFER_SIZE)): ByteReadChannel = synchronized(lock) {
        check(!closed) { "Local socket closed" }
        check(readChannel == null) { "Read channel already opened" }
        object : FileDescriptorReadChannel(socket.fileDescriptor, handler, buffer) {
            override val eventAwaiter get() = this@ALocalSocket.eventAwaiter
            override fun closeDescriptor() = socket.shutdownInput()
            override fun closeEvents() { }
        }.also { readChannel = it }
    }

    /**
     * Opens the write half of this socket as a [ByteWriteChannel].
     *
     * Closing or cancelling the returned channel shuts down this socket's output half. Use [close] to close the whole
     * socket.
     */
    fun openWriteChannel(buffer: ByteArray = ByteArray(DEFAULT_BUFFER_SIZE)): ByteWriteChannel = synchronized(lock) {
        check(!closed) { "Local socket closed" }
        check(writeChannel == null) { "Write channel already opened" }
        object : FileDescriptorWriteChannel(socket.fileDescriptor, handler, buffer) {
            override val eventAwaiter get() = this@ALocalSocket.eventAwaiter
            override fun closeDescriptor() = socket.shutdownOutput()
            override fun closeEvents() { }
        }.also { writeChannel = it }
    }

    override fun close() {
        var readChannel: ByteReadChannel? = null
        var writeChannel: ByteWriteChannel? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            readChannel = this.readChannel
            writeChannel = this.writeChannel
        }
        val cause = CancellationException("Local socket closed")
        readChannel?.cancel(cause)
        writeChannel?.cancel(cause)
        eventAwaiter.close()
        socket.close()
    }
}
