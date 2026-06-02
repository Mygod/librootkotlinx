package be.mygod.librootkotlinx.impl

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.LocalServerSocket
import android.os.Handler
import android.os.Looper
import android.os.Process
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.net.ALocalServerSocket
import be.mygod.librootkotlinx.net.ALocalSocket
import io.ktor.utils.io.discard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Owns the app/root rendezvous socket used to keep a detached root process tied to one app-side server.
 */
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

    companion object {
        const val SOCKET_ENV = "LIBROOTKOTLINX_OWNERSHIP_SOCKET"

        fun connectFromRootProcess(): LocalSocket = try {
            val socketName = System.getenv(SOCKET_ENV) ?: throw IllegalStateException("$SOCKET_ENV is not set")
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(socketName))
                socket
            } catch (e: Throwable) {
                try {
                    socket.close()
                } catch (closeError: IOException) {
                    e.addSuppressed(closeError)
                }
                throw e
            }
        } catch (e: Throwable) {
            Logger.me.e("Failed to connect root process ownership socket", e)
            e.printStackTrace()
            System.err.flush()
            exitProcess(1)
        }

        fun monitorRootProcess(socket: LocalSocket, scope: CoroutineScope) {
            val handler = Handler(Looper.getMainLooper())
            scope.launch {
                val channel = ALocalSocket(socket, handler).openReadChannel()
                var ownershipLost = false
                try {
                    channel.discard()
                    Logger.me.w("Root process ownership revoked")
                    ownershipLost = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logger.me.w("Root process ownership monitor failed", e)
                    ownershipLost = true
                } finally {
                    channel.cancel(null)
                    if (ownershipLost) exitProcess(0)
                }
            }
        }
    }
}
