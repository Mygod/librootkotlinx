package be.mygod.librootkotlinx.impl

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import be.mygod.librootkotlinx.io.openReadChannel
import io.ktor.utils.io.discard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException
import kotlin.system.exitProcess

internal object RootProcessMain {
    private const val TAG = "RootServer"
    const val OWNERSHIP_SOCKET_ENV = "LIBROOTKOTLINX_OWNERSHIP_SOCKET"

    @JvmStatic
    @Suppress("DEPRECATION")
    fun main(args: Array<String>) {
        if (args.size < 3) {
            System.err.println("RootProcessMain requires component, uid, and action arguments")
            System.err.flush()
            exitProcess(1)
        }
        val ownership = try {
            connectOwnership()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to connect root process ownership socket", e)
            e.printStackTrace()
            System.err.flush()
            exitProcess(1)
        }
        try {
            // Mirrors libsu RootServerMain.main after argument validation, except the first two lines that close
            // stdout/stderr. Keeping those descriptors open is the point of this entry point.
            // https://github.com/topjohnwu/libsu/blob/8314fa2f48b8421cdb1d38c472c518167b1cba9d/service/jar/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java#L70-L88
            Looper.prepareMainLooper()
            monitorOwnership(ownership)
            val rootServerMain = Class.forName("com.topjohnwu.superuser.internal.RootServerMain")
            val constructor = rootServerMain.getDeclaredConstructor(Array<String>::class.java)
            constructor.isAccessible = true
            constructor.newInstance(args as Any)
            Looper.loop()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in RootProcessMain", e)
            e.printStackTrace()
            System.err.flush()
            exitProcess(1)
        }
        exitProcess(1)
    }

    private fun connectOwnership(): LocalSocket {
        val socketName = System.getenv(OWNERSHIP_SOCKET_ENV)
            ?: throw IllegalStateException("$OWNERSHIP_SOCKET_ENV is not set")
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketName))
            val accepted = socket.inputStream.read()
            if (accepted != RootProcessOwnership.ACCEPTED.toInt()) {
                throw EOFException("Root process ownership socket closed before startup was accepted")
            }
            return socket
        } catch (e: Throwable) {
            try {
                socket.close()
            } catch (closeError: IOException) {
                e.addSuppressed(closeError)
            }
            throw e
        }
    }

    private fun monitorOwnership(socket: LocalSocket) {
        val looper = Looper.getMainLooper()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            val channel = ParcelFileDescriptor.dup(socket.fileDescriptor).openReadChannel(looper)
            try {
                channel.discard()
                Log.w(TAG, "Root process ownership revoked")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Root process ownership monitor failed", e)
            } finally {
                channel.cancel(null)
                try {
                    socket.close()
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to close root process ownership socket", e)
                }
                exitProcess(0)
            }
        }
    }
}
