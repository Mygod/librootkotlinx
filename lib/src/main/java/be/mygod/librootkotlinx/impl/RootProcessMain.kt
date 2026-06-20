package be.mygod.librootkotlinx.impl

import android.content.Context
import android.os.Looper
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.systemContext

/**
 * Root-side runtime for the owned backend.
 *
 * RootProcessBootstrap creates the package Context first, so this class is loaded through the framework-created package
 * class loader instead of the initial base-APK app_process class loader. It intentionally does not instantiate
 * RootServiceServer, so libsu's synchronous broadcast and root-side no-client timeout are absent from startup.
 */
internal object RootProcessMain {
    @JvmStatic
    fun main(context: Context, userId: Int, authority: String, token: String) {
        val previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Logger.me.onRootUncaughtException(thread, throwable)
            } finally {
                previousUncaughtExceptionHandler?.uncaughtException(thread, throwable)
            }
        }
        systemContext = context
        val service = RootCommandService(Looper.myLooper()!!::quitSafely)
        if (!RootServiceHandoffClient.handoff(context, authority, token, service.asBinder(), userId)) {
            throw IllegalStateException("Root service handoff rejected")
        }
        Looper.loop()
    }
}
