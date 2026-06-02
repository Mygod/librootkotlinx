package be.mygod.librootkotlinx.impl

import android.content.Context
import android.os.Looper
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
    fun main(context: Context, userId: Int) {
        systemContext = context
        val authority = System.getenv(RootServiceHandoff.AUTHORITY_ENV)
            ?: throw IllegalStateException("${RootServiceHandoff.AUTHORITY_ENV} is not set")
        val token = System.getenv(RootServiceHandoff.TOKEN_ENV)
            ?: throw IllegalStateException("${RootServiceHandoff.TOKEN_ENV} is not set")
        val service = RootCommandService(Looper.myLooper()!!::quitSafely)
        if (!RootServiceHandoffClient.handoff(context, authority, token, service.asBinder(), userId)) {
            throw IllegalStateException("Root service handoff rejected")
        }
        Looper.loop()
    }
}
