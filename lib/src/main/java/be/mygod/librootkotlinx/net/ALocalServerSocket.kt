package be.mygod.librootkotlinx.net

import android.net.LocalServerSocket
import android.os.Build
import android.os.Handler
import android.os.StrictMode
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
    private val dispatcher = handler.asCoroutineDispatcher("local-server-socket").immediate

    init {
        socket.fileDescriptor.isNonblocking = true
    }

    suspend fun accept() = withContext(dispatcher) {
        while (true) {
            eventAwaiter.await(input = true)
            try {
                // API 31's BlockGuardOs skips StrictMode network policy for nonblocking AF_UNIX accept.
                val accepted = if (Build.VERSION.SDK_INT < 31) {
                    val policy = StrictMode.getThreadPolicy()
                    try {
                        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(policy).permitNetwork().build())
                        socket.accept()
                    } finally {
                        StrictMode.setThreadPolicy(policy)
                    }
                } else {
                    socket.accept()
                }
                return@withContext ALocalSocket(accepted, handler)
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
