package be.mygod.librootkotlinx.net

import android.net.LocalServerSocket
import android.os.Handler
import android.system.ErrnoException
import android.system.OsConstants
import be.mygod.librootkotlinx.io.FileDescriptorEventAwaiter
import be.mygod.librootkotlinx.io.isNonblocking
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/**
 * Async wrapper for [LocalServerSocket].
 */
class ALocalServerSocket(val socket: LocalServerSocket, private val handler: Handler) : Closeable {
    private val eventAwaiter = FileDescriptorEventAwaiter(socket.fileDescriptor, handler.looper.queue)
    private val dispatcher = handler.asCoroutineDispatcher("local-server-socket")

    init {
        socket.fileDescriptor.isNonblocking = true
    }

    suspend fun accept() = withContext(dispatcher) {
        while (true) {
            eventAwaiter.await(input = true)
            try {
                return@withContext ALocalSocket(socket.accept(), handler)
            } catch (e: IOException) {
                if ((e.cause as? ErrnoException)?.errno != OsConstants.EAGAIN) throw e
            }
        }
        error("Unreachable")
    }

    override fun close() {
        eventAwaiter.close()
        socket.close()
    }
}
