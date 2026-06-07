package be.mygod.librootkotlinx.impl

import android.content.Context
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import be.mygod.librootkotlinx.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Owns one app-side attempt to start the root process and receive its command-service Binder.
 */
internal class RootServiceConnection(
    private val context: Context,
    private val niceName: String,
    private val deathRecipient: IBinder.DeathRecipient,
    private val handleRootLifecycle: suspend (
        Process,
        ParcelFileDescriptor,
        ParcelFileDescriptor,
        ParcelFileDescriptor,
    ) -> Unit,
    private val rootLifecycleCoroutineContext: CoroutineContext,
    private val canStartRootProcess: () -> Boolean,
    private val onConnected: (Connected) -> Boolean,
    private val onCloseRequested: (Throwable) -> Boolean,
    private val onStartupFailed: (RootServiceConnection, Throwable) -> Unit,
) {
    private var handoff: RootServiceHandoff.Registration? = null
    private var rootProcess: RootProcessHandle? = null
    private var rootProcessJob: Job? = null
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
                packageName = context.packageName,
                packageCodePath = context.applicationInfo.sourceDir?.takeIf(String::isNotEmpty)
                    ?: context.packageCodePath,
                niceName = niceName,
                codeCacheDir = {
                    if (Build.VERSION.SDK_INT >= 24) context.createDeviceProtectedStorageContext() else {
                        context
                    }.codeCacheDir
                },
                handoffAuthority = RootServiceHandoff.authority(context),
                handoffToken = handoff.token,
                handleRootLifecycle = handleRootLifecycle,
            )
        } catch (e: Throwable) {
            handoff.close()
            this.handoff = null
            throw e
        }.also { this.rootProcess = it }
        rootProcessJob = scope.launch {
            try {
                if (AppProcess.hasStartupAgents(context)) Logger.me.w("JVMTI agent is enabled. Please enable the " +
                        "'Always install with package manager' option in Android Studio.")
                rootProcess.run(rootServiceConnected, rootLifecycleCoroutineContext)
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
        rootProcessJob?.cancel(cause)
        rootProcess?.close()
        rootProcessJob?.join()
        handoff?.close()
        handoff = null
        connected = null
    }

    class Connected internal constructor(
        internal val connection: RootServiceConnection,
        internal val binder: IBinder,
        val service: IRootCommandService,
    ) {
        private val closed = AtomicBoolean()

        fun close(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            try {
                binder.unlinkToDeath(connection.deathRecipient, 0)
            } catch (e: RuntimeException) {
                Logger.me.w("Failed to unlink root service death recipient $reason", e)
            }
            closeService(reason)
            if (connection.connected === this) connection.connected = null
        }

        fun closeFromCallback(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            try {
                binder.unlinkToDeath(connection.deathRecipient, 0)
            } catch (e: RuntimeException) {
                Logger.me.w("Failed to unlink root service death recipient $reason", e)
            }
            closeService(reason)
            if (connection.connected === this) connection.connected = null
        }

        private fun closeService(reason: String) {
            try {
                service.close()
            } catch (e: DeadObjectException) {
                Logger.me.d("Root service already dead $reason", e)
            } catch (e: RemoteException) {
                Logger.me.w("Failed to close root service $reason", e)
            }
        }
    }
}
