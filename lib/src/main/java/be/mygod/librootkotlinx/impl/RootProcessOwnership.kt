package be.mygod.librootkotlinx.impl

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.os.Process
import android.system.ErrnoException
import android.system.OsConstants
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.io.FileDescriptorEventAwaiter
import be.mygod.librootkotlinx.io.isNonblocking
import kotlinx.coroutines.CancellationException
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class RootProcessOwnership : Closeable {
    val socketName = "librootkotlinx.${Process.myPid()}.${UUID.randomUUID()}"
    private val serverSocket = LocalServerSocket(socketName)
    private val closed = AtomicBoolean()
    private var socket: LocalSocket? = null

    suspend fun accept() {
        val descriptor = serverSocket.fileDescriptor
        descriptor.isNonblocking = true
        val awaiter = FileDescriptorEventAwaiter(descriptor, Handler(Looper.getMainLooper()))
        try {
            while (true) {
                if (closed.get()) throw CancellationException("Root process ownership closed")
                awaiter.await(MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT)
                val accepted = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    if (e.isEagain()) continue else throw e
                }
                try {
                    val uid = accepted.peerCredentials.uid
                    if (uid != 0) {
                        Logger.me.w("Rejected root process ownership connection from uid=$uid")
                        close(accepted)
                        continue
                    }
                    synchronized(this) {
                        if (closed.get() || socket != null) false else {
                            socket = accepted
                            true
                        }
                    }.also {
                        if (!it) {
                            accepted.close()
                            throw CancellationException("Root process ownership closed")
                        }
                    }
                    closeServerSocket()
                    return
                } catch (e: IOException) {
                    close(accepted)
                    throw e
                }
            }
        } finally {
            awaiter.close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val socket = synchronized(this) {
            socket.also { socket = null }
        }
        close(socket)
        closeServerSocket()
    }

    private fun closeServerSocket() = close(serverSocket)

    private fun IOException.isEagain() = (cause as? ErrnoException)?.errno == OsConstants.EAGAIN

    private fun close(closeable: Closeable?) {
        if (closeable == null) return
        try {
            closeable.close()
        } catch (e: IOException) {
            Logger.me.w("Failed to close root process ownership resource", e)
        }
    }
}
