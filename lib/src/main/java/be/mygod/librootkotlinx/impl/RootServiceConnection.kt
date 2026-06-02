package be.mygod.librootkotlinx.impl

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import be.mygod.librootkotlinx.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one app-side attempt to start the root process and receive its command-service Binder.
 */
internal class RootServiceConnection(
    context: Context,
    private val deathRecipient: IBinder.DeathRecipient,
    private val handleRootIo: suspend (ParcelFileDescriptor, ParcelFileDescriptor, ParcelFileDescriptor) -> Unit,
    private val canStartRootProcess: () -> Boolean,
    private val onConnected: (Connected) -> Boolean,
    private val onCloseRequested: (Throwable) -> Boolean,
    private val onStartupFailed: (RootServiceConnection, Throwable) -> Unit,
) {
    private val packageName = context.packageName
    private val packageCodePaths = context.applicationInfo.run {
        // Mirrors LoadedApk.makePaths code path assembly for app_process CLASSPATH.
        // https://android.googlesource.com/platform/frameworks/base/+/android-7.0.0_r1/core/java/android/app/LoadedApk.java#316
        sequenceOf(sourceDir).plus(splitSourceDirs.orEmpty().asSequence()).filterNotNull().toList()
    }
    private val packageCodePath = packageCodePaths.joinToString(File.pathSeparator).takeIf(String::isNotEmpty)
            ?: context.packageCodePath
    private val packageNativeLibrarySearchPath = context.applicationInfo.run {
        buildList {
            nativeLibraryDir?.takeIf(String::isNotEmpty)?.let(::add)
            // Mirrors LoadedApk.makePaths adding apk!/lib/<abi> entries for non-extracted native libraries. Since
            // ApplicationInfo.primaryCpuAbi is hidden, use the ABI list matching this app_process bitness.
            // LoadedApk builds code paths from sourceDir/splitSourceDirs, then adds apk!/lib/<primaryCpuAbi> paths.
            // https://android.googlesource.com/platform/frameworks/base/+/android-7.0.0_r1/core/java/android/app/LoadedApk.java#383
            // https://android.googlesource.com/platform/frameworks/base/+/android-7.0.0_r1/core/java/android/app/LoadedApk.java#392
            // https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/content/pm/ApplicationInfo.java#538
            val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
            if (abis.isEmpty()) return@buildList
            packageCodePaths.forEach { apk -> if (apk.endsWith(".apk")) for (abi in abis) add("$apk!/lib/$abi") }
        }.joinToString(File.pathSeparator).takeIf(String::isNotEmpty)
    }
    private val codeCacheDir = {
        if (Build.VERSION.SDK_INT >= 24) context.createDeviceProtectedStorageContext() else {
            context
        }.codeCacheDir
    }
    private val handoffAuthority = RootServiceHandoff.authority(context)
    private var handoff: RootServiceHandoff.Registration? = null
    private var rootProcess: RootProcessHandle? = null
    private var startupJob: Job? = null
    @Volatile
    private var connected: Connected? = null

    @Synchronized
    private fun onServiceConnected(binder: IBinder): Boolean {
        if (connected != null) return false
        val remote = IRootCommandService.Stub.asInterface(binder)
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (e: RemoteException) {
            onCloseRequested(e)
            return false
        }
        val connected = Connected(this, binder, remote)
        this.connected = connected
        return if (onConnected(connected)) true else {
            connected.closeFromCallback("after closed handoff")
            false
        }
    }

    fun bind(scope: CoroutineScope, rootServiceConnected: Job) {
        if (!canStartRootProcess()) throw CancellationException("Root startup cancelled")
        val handoff = RootServiceHandoff.register(::onServiceConnected).also { this.handoff = it }
        val rootProcess = try {
            RootProcessHandle(
                packageName = packageName,
                packageCodePath = packageCodePath,
                packageNativeLibrarySearchPath = packageNativeLibrarySearchPath,
                codeCacheDir = codeCacheDir,
                handoffAuthority = handoffAuthority,
                handoffToken = handoff.token,
                handleRootIo = handleRootIo,
            )
        } catch (e: Throwable) {
            handoff.close()
            this.handoff = null
            throw e
        }.also { this.rootProcess = it }
        startupJob = scope.launch {
            try {
                rootProcess.run(rootServiceConnected)
            } catch (e: Throwable) {
                if (e !is CancellationException) onStartupFailed(this@RootServiceConnection, e)
            }
        }
    }

    fun markStartupFailed() {
        handoff?.close()
        handoff = null
    }

    suspend fun close(cause: CancellationException, accepted: Connected?) {
        val delivered = connected
        // Revoking root-process ownership exits the root process, so close the Binder service first.
        accepted?.close("during cleanup")
        if (delivered !== accepted) delivered?.close("during cleanup")
        cancelStartup(cause)
        joinStartup()
        handoff?.close()
        handoff = null
        connected = null
    }

    private fun cancelStartup(cause: CancellationException) {
        startupJob?.cancel(cause)
        rootProcess?.close()
    }

    private suspend fun joinStartup() {
        startupJob?.join()
    }

    class Connected internal constructor(
        internal val connection: RootServiceConnection,
        internal val binder: IBinder,
        val service: IRootCommandService,
    ) {
        private val closed = AtomicBoolean()

        suspend fun close(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            try {
                binder.unlinkToDeath(connection.deathRecipient, 0)
            } catch (e: RuntimeException) {
                Logger.me.w("Failed to unlink root service death recipient $reason", e)
            }
            try {
                service.close()
            } catch (e: RemoteException) {
                Logger.me.w("Failed to close root service $reason", e)
            }
            if (connection.connected === this) connection.connected = null
        }

        fun closeFromCallback(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            try {
                binder.unlinkToDeath(connection.deathRecipient, 0)
            } catch (e: RuntimeException) {
                Logger.me.w("Failed to unlink root service death recipient $reason", e)
            }
            try {
                service.close()
            } catch (e: RemoteException) {
                Logger.me.w("Failed to close root service $reason", e)
            }
            if (connection.connected === this) connection.connected = null
        }
    }
}
