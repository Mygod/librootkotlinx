package be.mygod.librootkotlinx.impl

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Looper
import android.os.UserHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.system.exitProcess

/**
 * Tiny root app_process entry point that lets the framework build the app package class loader before the real runtime
 * starts. Keep this class independent from librootkotlinx runtime types; it initially runs from the base APK class path.
 *
 * libsu source:
 * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/jar/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java#L57-L68
 * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/jar/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java#L145-L178
 */
internal object RootProcessBootstrap {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 5) {
            System.err.println(
                "RootProcessBootstrap requires package, uid, ownership socket, handoff authority and token arguments")
            System.err.flush()
            exitProcess(1)
        }
        val ownership = RootProcessOwnership.connectFromRootProcess(args[2])
        @Suppress("DEPRECATION") Looper.prepareMainLooper()
        val processJob = SupervisorJob()
        val processScope = CoroutineScope(processJob + Dispatchers.Main.immediate)
        try {
            RootProcessOwnership.monitorRootProcess(ownership, processScope)
            val userId = args[1].toInt()
            patchLgeResourcesIfNeeded()
            val context = createRootPackageContext(args[0], userId)
            val classLoader = context.classLoader
            // Mirrors LoadedApk.initializeJavaContextClassLoader keeping thread-context lookups on the package loader.
            // The root process hosts only this package after Bootstrap creates the package context.
            // AOSP: https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/app/LoadedApk.java#1218
            Thread.currentThread().contextClassLoader = classLoader
            classLoader.loadClass(RootProcessMain::class.java.name).getDeclaredMethod("main",
                Context::class.java, Int::class.javaPrimitiveType, String::class.java, String::class.java)(null,
                context, userId, args[3], args[4])
        } finally {
            processJob.cancel()
        }
        exitProcess(1)
    }

    private const val CONTEXT_FLAGS = Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
    /**
     * Mirrors libsu RootServerMain's system-context and per-user package-context bootstrap. Unlike libsu's main.jar
     * trampoline, this class itself lives in the base APK, then switches to the framework-created package class loader.
     *
     * libsu source:
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/jar/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java#L57-L68
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/jar/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java#L158-L178
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun createRootPackageContext(packageName: String, userId: Int): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val systemContext = activityThreadClass.getMethod("getSystemContext")(
            activityThreadClass.getMethod("systemMain")(null)) as Context
        return try {
            val userHandle = try {
                UserHandle::class.java.getDeclaredMethod("of", Integer.TYPE)(null, userId)
            } catch (e: NoSuchMethodException) {
                if (Build.VERSION.SDK_INT >= 24) {
                    System.err.println("Unexpected UserHandle.of method missing")
                    e.printStackTrace()
                    System.err.flush()
                }
                UserHandle::class.java.getDeclaredConstructor(Integer.TYPE).newInstance(userId)
            }
            systemContext.javaClass.getDeclaredMethod("createPackageContextAsUser", String::class.java, Integer.TYPE,
                UserHandle::class.java)(systemContext, packageName, CONTEXT_FLAGS, userHandle) as Context
        } catch (e: Throwable) {
            System.err.println("Failed to create package context as user: $userId")
            e.printStackTrace()
            systemContext.createPackageContext(packageName, CONTEXT_FLAGS)
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
