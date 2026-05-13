package be.mygod.librootkotlinx.impl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.impl.libsu.PendingRootServiceBind
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one app-side attempt to bind the libsu RootService and, when needed, start the detached root process.
 */
internal class RootServiceConnection(
    context: Context,
    private val deathRecipient: IBinder.DeathRecipient,
    private val handleRootIo: suspend (ParcelFileDescriptor, ParcelFileDescriptor, ParcelFileDescriptor) -> Unit,
    private val canStartRootProcess: () -> Boolean,
    private val onConnected: (Connected) -> Boolean,
    private val onCloseRequested: (Throwable) -> Boolean,
    private val onUnexpectedExit: () -> Throwable,
    private val onStartupFailed: (RootServiceConnection, Throwable) -> Unit,
) : ServiceConnection {
    private val packageCodePath = context.packageCodePath
    private val intent = Intent(context, RootCommandService::class.java)
    private var pendingBind: PendingRootServiceBind? = null
    private var clearPending = false
    private var rootProcess: RootProcessHandle? = null
    private var startupJob: Job? = null

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val remote = IRootCommandService.Stub.asInterface(binder)
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (e: RemoteException) {
            if (!onCloseRequested(e)) unbindNow("Root service unbind after dead binding failed")
            return
        }
        val connected = Connected(this, binder, remote)
        if (!onConnected(connected)) connected.closeFromCallback("after closed bind")
    }

    override fun onServiceDisconnected(name: ComponentName) {
        onCloseRequested(onUnexpectedExit())
    }

    override fun onBindingDied(name: ComponentName) {
        onCloseRequested(onUnexpectedExit())
    }

    override fun onNullBinding(name: ComponentName) {
        onCloseRequested(IllegalStateException("Root service returned null binding"))
    }

    suspend fun bind(scope: CoroutineScope, rootServiceConnected: Job) {
        val rootProcess = withContext(Dispatchers.Main.immediate) {
            val pendingBind = PendingRootServiceBind(intent).also { this@RootServiceConnection.pendingBind = it }
            val task = try {
                RootService.bindOrTask(
                    intent,
                    Dispatchers.Main.immediate.asExecutor(),
                    this@RootServiceConnection,
                ).also {
                    if (it != null && pendingBind.ownsStartupIfQueued) {
                        pendingBind.captureQueuedTask()
                        clearPending = true
                    }
                }
            } catch (e: Throwable) {
                if (pendingBind.ownsStartupIfQueued) clearPending = true
                throw e
            }
            if (task != null && canStartRootProcess()) {
                try {
                    RootProcessHandle(packageCodePath, task, handleRootIo)
                } catch (e: Throwable) {
                    if (pendingBind.ownsStartupIfQueued) clearPending = true
                    throw e
                }.also { this@RootServiceConnection.rootProcess = it }
            } else null
        }
        if (rootProcess != null) startupJob = scope.launch {
            try {
                rootProcess.run(rootServiceConnected)
            } catch (e: Throwable) {
                if (e !is CancellationException) onStartupFailed(this@RootServiceConnection, e)
            }
        }
    }

    fun markStartupFailed() {
        clearPending = true
    }

    fun cancelStartup(cause: CancellationException) {
        startupJob?.cancel(cause)
        rootProcess?.close()
    }

    suspend fun joinStartup() {
        startupJob?.join()
    }

    suspend fun cleanupPending() {
        if (!clearPending) return
        val pendingBind = pendingBind ?: return
        withContext(Dispatchers.Main.immediate) {
            try {
                pendingBind.cancel()
            } catch (e: Throwable) {
                Logger.me.w("Failed to clean up libsu pending RootService bind", e)
            }
        }
    }

    suspend fun unbind(message: String) {
        withContext(Dispatchers.Main.immediate) {
            unbindNow(message)
        }
    }

    private fun unbindNow(message: String) {
        try {
            RootService.unbind(this)
        } catch (e: RuntimeException) {
            Logger.me.w(message, e)
        }
    }

    class Connected internal constructor(
        internal val connection: RootServiceConnection,
        internal val binder: IBinder,
        val service: IRootCommandService,
    ) {
        suspend fun close(reason: String) {
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
            connection.unbind("Failed to unbind root service connection $reason")
        }

        fun closeFromCallback(reason: String) {
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
            connection.unbindNow("Failed to unbind root service connection $reason")
        }
    }
}
