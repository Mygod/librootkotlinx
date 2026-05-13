package be.mygod.librootkotlinx.impl

import android.net.LocalServerSocket
import android.os.Handler
import android.os.Looper
import android.os.Process
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.net.ALocalServerSocket
import be.mygod.librootkotlinx.net.ALocalSocket
import kotlinx.coroutines.CancellationException
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class RootProcessOwnership : Closeable {
    val socketName = "librootkotlinx.${Process.myPid()}.${UUID.randomUUID()}"
    private val serverSocket = ALocalServerSocket(LocalServerSocket(socketName), Handler(Looper.getMainLooper()))
    private val closed = AtomicBoolean()
    private var socket: ALocalSocket? = null

    suspend fun accept() {
        while (true) {
            if (closed.get()) throw CancellationException("Root process ownership closed")
            val accepted = serverSocket.accept()
            try {
                val uid = accepted.socket.peerCredentials.uid
                if (uid != 0) {
                    Logger.me.w("Rejected root process ownership connection from uid=$uid")
                    close(accepted)
                    continue
                }
                val acceptedByOwner = synchronized(this) {
                    if (closed.get() || socket != null) false else {
                        socket = accepted
                        true
                    }
                }
                if (!acceptedByOwner) {
                    accepted.close()
                    throw CancellationException("Root process ownership closed")
                }
                closeServerSocket()
                return
            } catch (e: IOException) {
                close(accepted)
                throw e
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val socket = synchronized(this) { socket.also { socket = null } }
        close(socket)
        closeServerSocket()
    }

    private fun closeServerSocket() = close(serverSocket)

    private fun close(closeable: Closeable?) {
        if (closeable != null) try {
            closeable.close()
        } catch (e: IOException) {
            Logger.me.w("Failed to close root process ownership resource", e)
        }
    }
}
