package be.mygod.librootkotlinx.impl

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Looper
import android.os.UserHandle
import be.mygod.librootkotlinx.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.system.exitProcess

/**
 * Root-side entry point for the owned backend.
 *
 * This ports only libsu RootServerMain's package-context bootstrap and LG resources workaround, then creates the
 * librootkotlinx command host directly. It intentionally does not instantiate RootServiceServer, so libsu's synchronous
 * broadcast and root-side no-client timeout are absent from startup.
 *
 * libsu source:
 * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/jar/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java#L57-L68
 * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/jar/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java#L145-L178
 */
internal object RootProcessMain {
    private const val PER_USER_RANGE = 100000

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            System.err.println("RootProcessMain requires package and uid arguments")
            System.err.flush()
            exitProcess(1)
        }
        val ownership = RootProcessOwnership.connectFromRootProcess()
        try {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            val processJob = SupervisorJob()
            val processScope = CoroutineScope(processJob + Dispatchers.Main.immediate)
            try {
                RootProcessOwnership.monitorRootProcess(ownership, processScope)
                patchLgeResourcesIfNeeded()
                val targetUid = args[1].toInt()
                val context = createRootPackageContext(args[0], targetUid)
                val authority = System.getenv(RootServiceHandoff.AUTHORITY_ENV)
                    ?: throw IllegalStateException("${RootServiceHandoff.AUTHORITY_ENV} is not set")
                val token = System.getenv(RootServiceHandoff.TOKEN_ENV)
                    ?: throw IllegalStateException("${RootServiceHandoff.TOKEN_ENV} is not set")
                val service = RootCommandService(context)
                if (!RootServiceHandoffClient.handoff(context, authority, token, service.asBinder(), targetUid)) {
                    throw IllegalStateException("Root service handoff rejected")
                }
                Looper.loop()
            } finally {
                processJob.cancel()
            }
        } catch (e: Throwable) {
            Logger.me.e("Error in RootProcessMain", e)
            e.printStackTrace()
            System.err.flush()
        }
        exitProcess(1)
    }

    /**
     * Mirrors libsu RootServerMain's system-context and per-user package-context bootstrap. The owned backend passes a
     * package name instead of a ComponentName because the command host is fixed and no arbitrary RootService component is
     * instantiated.
     *
     * libsu source:
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/jar/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java#L57-L68
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/jar/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java#L158-L178
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun createRootPackageContext(packageName: String, targetUid: Int): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("systemMain")(null)
        val systemContext = activityThreadClass.getMethod("getSystemContext")(activityThread) as Context
        val flags = Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
        val userId = targetUid / PER_USER_RANGE
        return try {
            val userHandle = try {
                UserHandle::class.java.getDeclaredMethod("of", Integer.TYPE)(null, userId)
            } catch (_: NoSuchMethodException) {
                UserHandle::class.java.getDeclaredConstructor(Integer.TYPE).newInstance(userId)
            }
            systemContext.javaClass.getDeclaredMethod("createPackageContextAsUser", String::class.java, Integer.TYPE,
                UserHandle::class.java)(systemContext, packageName, flags, userHandle) as Context
        } catch (e: Throwable) {
            Logger.me.w("Failed to create package context as user: $userId", e)
            systemContext.createPackageContext(packageName, flags)
        }
    }

    /**
     * Mirrors libsu RootServerMain's LG IntegrityManager workaround. It stays best-effort: non-LG devices and reflection
     * drift skip the patch, matching libsu's ignored ReflectiveOperationException path.
     *
     * libsu source:
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/jar/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java#L145-L156
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun patchLgeResourcesIfNeeded() {
        try {
            Class.forName("com.lge.systemservice.core.integrity.IntegrityManager")
            val wrapper = ResourcesWrapper(Resources.getSystem())
            Resources::class.java.getDeclaredField("mSystem").apply {
                isAccessible = true
                set(null, wrapper)
            }
        } catch (_: ReflectiveOperationException) { }
    }

    /**
     * Mirrors libsu RootServerMain.ResourcesWrapper so the replacement Resources keeps the original ResourcesImpl while
     * returning false for missing LG framework booleans.
     *
     * libsu source:
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/jar/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java#L190-L212
     */
    private class ResourcesWrapper(res: Resources) : Resources(res.assets, res.displayMetrics, res.configuration) {
        init {
            val getImpl = Resources::class.java.getDeclaredMethod("getImpl").apply { isAccessible = true }
            Resources::class.java.getDeclaredMethod("setImpl", getImpl.returnType).apply {
                isAccessible = true
                invoke(this@ResourcesWrapper, getImpl(res))
            }
        }

        override fun getBoolean(id: Int): Boolean = try {
            super.getBoolean(id)
        } catch (_: NotFoundException) {
            false
        }
    }
}
