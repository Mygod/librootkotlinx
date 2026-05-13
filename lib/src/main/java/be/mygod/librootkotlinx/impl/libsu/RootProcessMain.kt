package be.mygod.librootkotlinx.impl.libsu

import android.os.Looper
import android.util.Log
import be.mygod.librootkotlinx.impl.RootProcessOwnership
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.system.exitProcess

/**
 * Root-side entry point that mirrors libsu's RootServerMain startup.
 *
 * libsu's RootServerMain closes stdout/stderr before creating the root-side Service. This entry point intentionally
 * keeps stdio open for [RootProcessLauncher]'s redirection, connects the app-owned [RootProcessOwnership] socket before
 * handing off to libsu, and then reflectively invokes RootServerMain with the original arguments. Ownership socket
 * connection and monitoring live in [RootProcessOwnership] so this file stays limited to libsu entry-point mirroring.
 */
internal object RootProcessMain {
    private const val TAG = "RootServer"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            System.err.println("RootProcessMain requires component, uid, and action arguments")
            System.err.flush()
            exitProcess(1)
        }
        val ownership = RootProcessOwnership.connectFromRootProcess()
        try {
            // Mirrors libsu RootServerMain.main after argument validation, except the first two lines that close
            // stdout/stderr. Keeping those descriptors open is the point of this entry point.
            // https://github.com/topjohnwu/libsu/blob/8314fa2f48b8421cdb1d38c472c518167b1cba9d/service/jar/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java#L70-L88
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            val processJob = SupervisorJob()
            try {
                RootProcessOwnership.monitorRootProcess(
                    ownership,
                    CoroutineScope(processJob + Dispatchers.Main.immediate),
                )
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
        }
        exitProcess(1)
    }
}
