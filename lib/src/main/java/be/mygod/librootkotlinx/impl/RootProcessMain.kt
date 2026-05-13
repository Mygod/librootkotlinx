package be.mygod.librootkotlinx.impl

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.util.Log
import be.mygod.librootkotlinx.net.ALocalSocket
import io.ktor.utils.io.discard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.system.exitProcess

internal object RootProcessMain {
    private const val TAG = "RootServer"
    const val OWNERSHIP_SOCKET_ENV = "LIBROOTKOTLINX_OWNERSHIP_SOCKET"

    @JvmStatic
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
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            val processJob = SupervisorJob()
            try {
                monitorOwnership(ownership, CoroutineScope(processJob + Dispatchers.Main.immediate))
                val rootServerMain = Class.forName("com.topjohnwu.superuser.internal.RootServerMain")
                val constructor = rootServerMain.getDeclaredConstructor(Array<String>::class.java)
                constructor.isAccessible = true
                constructor.newInstance(args as Any)
                Looper.loop()
            } finally {
                processJob.cancel()
            }
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

    private fun monitorOwnership(socket: LocalSocket, scope: CoroutineScope) {
        val handler = Handler(Looper.getMainLooper())
        scope.launch {
            val channel = ALocalSocket(socket, handler).openReadChannel()
            var ownershipLost = false
            try {
                channel.discard()
                Log.w(TAG, "Root process ownership revoked")
                ownershipLost = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Root process ownership monitor failed", e)
                ownershipLost = true
            } finally {
                channel.cancel(null)
                if (ownershipLost) exitProcess(0)
            }
        }
    }
}
