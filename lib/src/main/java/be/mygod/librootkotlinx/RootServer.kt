package be.mygod.librootkotlinx

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcelable
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableObjectList
import be.mygod.librootkotlinx.impl.IRootCommandCallback
import be.mygod.librootkotlinx.impl.IRootCommandService
import be.mygod.librootkotlinx.impl.PendingRootServiceBind
import be.mygod.librootkotlinx.impl.RootCommandRequest
import be.mygod.librootkotlinx.impl.RootCommandResponse
import be.mygod.librootkotlinx.impl.RootCommandService
import be.mygod.librootkotlinx.impl.RootProcessLauncher
import be.mygod.librootkotlinx.impl.RootProcessOwnership
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RootServer internal constructor() {
    private sealed class Callback(
        protected val server: RootServer,
        val index: Long,
        protected val classLoader: ClassLoader?,
    ) {
        var active = true

        abstract fun close(cause: Throwable)
        abstract fun shouldRemove(status: Int): Boolean
        abstract operator fun invoke(response: RootCommandResponse)

        class Ordinary(
            server: RootServer,
            index: Long,
            classLoader: ClassLoader?,
            private val result: CompletableDeferred<Parcelable?>,
        ) : Callback(server, index, classLoader) {
            override fun close(cause: Throwable) {
                if (cause is CancellationException) result.cancel(cause)
                else result.completeExceptionally(cause)
            }
            override fun shouldRemove(status: Int) = true
            override fun invoke(response: RootCommandResponse) {
                if (response.status == RootCommandResponse.SUCCESS) {
                    result.complete(response.payload)
                } else {
                    result.completeExceptionally(response.readException(classLoader))
                }
            }
        }

        class Flow(
            server: RootServer,
            index: Long,
            classLoader: ClassLoader?,
            private val channel: SendChannel<Parcelable?>,
        ) : Callback(server, index, classLoader) {
            override fun close(cause: Throwable) {
                channel.close(cause)
            }
            override fun shouldRemove(status: Int) = status != RootCommandResponse.SUCCESS
            override fun invoke(response: RootCommandResponse) {
                when (response.status) {
                    RootCommandResponse.SUCCESS -> {
                        val result = channel.trySend(response.payload)
                        if (result.isClosed) {
                            server.cancelRemote(index, this)
                        } else if (result.isFailure) {
                            val cause = result.exceptionOrNull()
                                    ?: IllegalStateException("Flow buffer rejected root command response")
                            server.cancelRemote(index, this)
                            channel.close(cause)
                        }
                    }
                    RootCommandResponse.COMPLETE -> channel.close()
                    else -> {
                        channel.close(response.readException(classLoader))
                    }
                }
            }
        }
    }

    private data class RegisteredCallback(val id: Long, val callback: Callback, val service: IRootCommandService)

    class UnexpectedExitException : RemoteException("Root service exited unexpectedly")

    val active get() = synchronized(callbackLookup) { service != null && closeCause == null }
    private val callbackLookup = MutableLongObjectMap<Callback>()
    private val startupJob = Job()
    private val startupScope = CoroutineScope(startupJob + Dispatchers.IO)
    private var counter = 0L
    private var service: IRootCommandService? = null
    private var serviceConnection: ServiceConnection? = null
    private var serviceBinder: IBinder? = null
    private var ownership: RootProcessOwnership? = null
    private var closeCause: Throwable? = null

    // Session-scoped callback: multiplex by id to avoid leaking per-command Binder callback refs.
    private val callback = object : IRootCommandCallback.Stub(), IBinder.DeathRecipient {
        override fun onResponse(id: Long, response: RootCommandResponse) {
            val callback = synchronized(callbackLookup) {
                callbackLookup[id]?.also {
                    if (it.shouldRemove(response.status)) {
                        callbackLookup.remove(id)
                        it.active = false
                    }
                }
            } ?: return
            try {
                callback(response)
            } catch (e: Throwable) {
                callback.close(e)
                Logger.me.w("Failed to handle root command response #$id", e)
            }
        }

        override fun binderDied() {
            closeInternal(UnexpectedExitException())
        }
    }

    /**
     * Initialize a RootServer by binding to a libsu RootService.
     *
     * Startup uses libsu's RootService bind handshake. [handleRootIo] is also used as a best-effort startup observer: if
     * it returns or fails before the service connects, initialization fails. Custom handlers must stay suspended until
     * startup completes; after startup, handler completion only ends IO handling. If the root-side service returns a null
     * binding on API 23-27, libsu does not dispatch a null-binding callback, so callers that need bounded startup latency
     * should apply their own timeout around initialization.
     *
     * @param context Any [Context] from the app.
     * @param handleRootIo Handler for observed root process stdin/stdout/stderr.
     */
    internal suspend fun init(
        context: Context,
        handleRootIo: suspend (ParcelFileDescriptor, ParcelFileDescriptor, ParcelFileDescriptor) -> Unit,
    ) {
        synchronized(callbackLookup) {
            require(service == null && closeCause == null) { "RootServer is already initialized or closed" }
        }
        try {
            withContext(Dispatchers.Main.immediate) { bind(context, handleRootIo) }
        } catch (e: Throwable) {
            closeInternal(e)
            throw e
        }
        Logger.me.d("Root server initialized")
    }

    private suspend fun bind(
        context: Context,
        handleRootIo: suspend (ParcelFileDescriptor, ParcelFileDescriptor, ParcelFileDescriptor) -> Unit,
    ) = suspendCancellableCoroutine { continuation ->
        var resumed = false
        val startupComplete = Job(startupJob)
        var pendingBind: PendingRootServiceBind? = null
        val cancelPendingBindOnCancellation = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            fun isStartupPending() = synchronized(callbackLookup) {
                closeCause == null && !resumed && continuation.isActive
            }

            fun resumeWithFailure(throwable: Throwable, clearEnRoute: Boolean = false) {
                val connection = this
                runOnMain {
                    val (shouldResume, shouldClose) = synchronized(callbackLookup) {
                        when (closeCause) {
                            null if !resumed && continuation.isActive -> {
                                resumed = true
                                true to false
                            }
                            null if resumed -> false to (serviceConnection === connection)
                            else -> false to false
                        }
                    }
                    if (shouldResume || shouldClose) {
                        startupComplete.complete()
                        closeInternal(throwable)
                    }
                    if (clearEnRoute) {
                        try {
                            pendingBind?.cancel()
                        } catch (e: Throwable) {
                            if (shouldResume) throwable.addSuppressed(e)
                            else Logger.me.w("Failed to clean up libsu pending RootService bind after bind failure", e)
                        }
                    }
                    if (shouldResume) {
                        unbind(connection, "Failed to unbind root service connection after bind failure")
                        continuation.resumeWithException(throwable)
                    }
                }
            }

            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val remote = IRootCommandService.Stub.asInterface(binder)
                try {
                    binder.linkToDeath(callback, 0)
                } catch (e: RemoteException) {
                    resumeWithFailure(e)
                    return
                }
                val shouldResume = synchronized(callbackLookup) {
                    if (closeCause != null || resumed || !continuation.isActive) false else {
                        resumed = true
                        service = remote
                        serviceConnection = this
                        serviceBinder = binder
                        cancelPendingBindOnCancellation.set(false)
                        true
                    }
                }
                if (shouldResume) {
                    startupComplete.complete()
                    continuation.resume(Unit)
                } else {
                    try {
                        binder.unlinkToDeath(callback, 0)
                    } catch (e: RuntimeException) {
                        Logger.me.w("Failed to unlink root service death recipient after cancelled bind", e)
                    }
                    try {
                        RootService.unbind(this)
                    } catch (e: RuntimeException) {
                        Logger.me.w("Failed to unbind root service connection after cancelled bind", e)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                closeInternal(UnexpectedExitException())
            }

            override fun onBindingDied(name: ComponentName) = resumeWithFailure(UnexpectedExitException())

            override fun onNullBinding(name: ComponentName) {
                resumeWithFailure(IllegalStateException("Root service returned null binding"))
            }
        }

        continuation.invokeOnCancellation { cause ->
            val cancellation = cause ?: CancellationException("Root server initialization cancelled")
            closeInternal(cancellation)
            runOnMain {
                if (!resumed && cancelPendingBindOnCancellation.getAndSet(false)) {
                    try {
                        pendingBind?.cancel()
                    } catch (e: Throwable) {
                        Logger.me.w("Failed to clean up libsu pending RootService bind after coroutine cancellation", e)
                    }
                }
                unbind(connection, "Failed to unbind root service connection after coroutine cancellation")
            }
        }

        val intent = Intent(context, RootCommandService::class.java)
        val pending = try {
            PendingRootServiceBind(intent)
        } catch (e: Throwable) {
            connection.resumeWithFailure(e)
            return@suspendCancellableCoroutine
        }
        pendingBind = pending
        val task = try {
            RootService.bindOrTask(
                intent,
                Dispatchers.Main.immediate.asExecutor(),
                connection,
            ).also {
                if (it != null && pending.ownsStartupIfQueued) {
                    pending.captureQueuedTask()
                    cancelPendingBindOnCancellation.set(true)
                }
            }
        } catch (e: Throwable) {
            connection.resumeWithFailure(e, clearEnRoute = pending.ownsStartupIfQueued)
            null
        }
        if (task != null) {
            val ownership = try {
                RootProcessOwnership()
            } catch (e: Throwable) {
                connection.resumeWithFailure(e, clearEnRoute = pending.ownsStartupIfQueued)
                return@suspendCancellableCoroutine
            }
            synchronized(callbackLookup) {
                if (closeCause == null && continuation.isActive) this.ownership = ownership else {
                    ownership.close()
                    return@suspendCancellableCoroutine
                }
            }
            val launcher = RootProcessLauncher(context, task, handleRootIo, ownership.socketName)
            startupScope.launch {
                try {
                    launcher.run(
                        startupScope,
                        startupComplete,
                        awaitStartupOwnership = { ownership.accept() },
                    )
                } catch (e: Throwable) {
                    if (e is CancellationException) return@launch
                    if (connection.isStartupPending()) connection.resumeWithFailure(e, clearEnRoute = true)
                }
            }
        }
    }

    @DelicateCoroutinesApi
    fun execute(command: RootCommandOneWay) {
        var serviceCallFailed = false
        try {
            synchronized(callbackLookup) {
                val remote = service ?: throw unavailableCauseLocked()
                try {
                    remote.executeOneWay(RootCommandRequest(command))
                } catch (e: RemoteException) {
                    serviceCallFailed = true
                    throw e
                }
            }
        } catch (e: RemoteException) {
            if (serviceCallFailed) closeAfterServiceCallFailure(e)
            throw e
        }
    }

    @Throws(RemoteException::class)
    suspend inline fun <T : Parcelable?, reified C : RootCommand<T>> execute(command: C) =
        execute(command, C::class.java.classLoader)
    @Throws(RemoteException::class)
    suspend fun <T : Parcelable?> execute(command: RootCommand<T>, classLoader: ClassLoader?): T {
        val result = CompletableDeferred<T>()
        @Suppress("UNCHECKED_CAST")
        val registered = send(command) {
            Callback.Ordinary(this, it, classLoader, result as CompletableDeferred<Parcelable?>)
        }
        try {
            return result.await()
        } finally {
            if (unregisterIfActive(registered)) cancelRemote(registered.id)
        }
    }

    /**
     * Creates a cold [Flow] backed by [source] in the root service.
     *
     * Each collection registers a new remote command and starts one root-side [RootFlow.flow] collection. Cancelling the
     * client collector unregisters that command and asks the root service to cancel its job. [capacity] and
     * [onBufferOverflow] configure the local response buffer. If the buffer rejects a root response, the remote command is
     * cancelled and the client flow fails.
     */
    inline fun <T : Parcelable?, reified C : RootFlow<T>> flow(
        source: C,
        capacity: Int = Channel.UNLIMITED,
        onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    ) = flow(source, C::class.java.classLoader, capacity, onBufferOverflow)

    /**
     * Creates a cold [Flow] backed by [source] in the root service.
     *
     * Each collection registers a new remote command and starts one root-side [RootFlow.flow] collection. Cancelling the
     * client collector unregisters that command and asks the root service to cancel its job. [capacity] and
     * [onBufferOverflow] configure the local response buffer. If the buffer rejects a root response, the remote command is
     * cancelled and the client flow fails.
     */
    fun <T : Parcelable?> flow(
        source: RootFlow<T>,
        classLoader: ClassLoader?,
        capacity: Int = Channel.UNLIMITED,
        onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    ): Flow<T> = callbackFlow<T> {
        @Suppress("UNCHECKED_CAST")
        val registered = this@RootServer.send(source) {
            Callback.Flow(this@RootServer, it, classLoader, this as SendChannel<Parcelable?>)
        }
        awaitClose {
            if (unregisterIfActive(registered)) cancelRemote(registered.id)
        }
    }.buffer(capacity, onBufferOverflow)

    private fun send(command: Parcelable, factory: (Long) -> Callback): RegisteredCallback {
        var serviceCallFailed = false
        try {
            return synchronized(callbackLookup) {
                val remote = service ?: throw unavailableCauseLocked()
                val id = counter++
                val callback = factory(id)
                try {
                    val registered = RegisteredCallback(id, callback, remote)
                    callbackLookup[id] = callback
                    remote.execute(id, RootCommandRequest(command), this.callback)
                    Logger.me.d("Sent #$id: $command")
                    registered
                } catch (e: Throwable) {
                    callbackLookup.remove(id)
                    callback.active = false
                    callback.close(e)
                    if (e is RemoteException) serviceCallFailed = true
                    throw e
                }
            }
        } catch (e: RemoteException) {
            if (serviceCallFailed) closeAfterServiceCallFailure(e)
            throw e
        }
    }

    private fun unregisterIfActive(registered: RegisteredCallback) = synchronized(callbackLookup) {
        if (!registered.callback.active) return@synchronized false
        callbackLookup.remove(registered.id, registered.callback).also {
            if (it) registered.callback.active = false
        }
    }

    private fun cancelRemote(id: Long, commandCallback: Callback? = null) {
        val remote = synchronized(callbackLookup) {
            if (commandCallback != null && callbackLookup.remove(id, commandCallback)) {
                commandCallback.active = false
            }
            service
        } ?: return
        try {
            remote.cancel(id, callback)
        } catch (e: RemoteException) {
            closeAfterServiceCallFailure(e)
        }
    }

    private fun closeAfterServiceCallFailure(cause: RemoteException) {
        val (service, connection) = closeInternal(cause)
        try {
            service?.close(callback)
        } catch (e: RemoteException) {
            Logger.me.w("Root service close failed after Binder failure", e)
        }
        connection?.let {
            runOnMain { unbind(it, "Root service unbind after Binder failure failed") }
        }
    }

    private fun unavailableCauseLocked() = closeCause ?: UnexpectedExitException()

    private fun closeInternal(cause: Throwable? = null): Pair<IRootCommandService?, ServiceConnection?> {
        val pendingCallbacks = MutableObjectList<Callback>()
        val binder: IBinder?
        val ownership: RootProcessOwnership?
        var completion = cause ?: CancellationException("Root server closed")
        val snapshot = synchronized(callbackLookup) {
            closeCause?.let { completion = it } ?: run { closeCause = completion }
            val snapshot = service to serviceConnection
            binder = serviceBinder
            ownership = this.ownership
            service = null
            serviceConnection = null
            serviceBinder = null
            this.ownership = null
            callbackLookup.forEachValue { pendingCallbacks.add(it) }
            callbackLookup.clear()
            snapshot
        }
        startupJob.cancel(completion.asCancellationException())
        ownership?.close()
        try {
            binder?.unlinkToDeath(callback, 0)
        } catch (e: RuntimeException) {
            Logger.me.w("Failed to unlink root service death recipient", e)
        }
        pendingCallbacks.forEach { callback ->
            callback.active = false
            callback.close(completion)
        }
        return snapshot
    }

    /**
     * Close the instance gracefully.
     */
    suspend fun close() {
        Logger.me.d("Shutting down from client")
        val (service, connection) = closeInternal()
        try {
            service?.close(callback)
        } catch (e: RemoteException) {
            Logger.me.w("Root service close failed", e)
        }
        connection?.let {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                unbind(it, "Root service unbind failed")
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        val dispatcher = Dispatchers.Main.immediate
        if (dispatcher.isDispatchNeeded(EmptyCoroutineContext)) {
            dispatcher.dispatch(EmptyCoroutineContext, Runnable { block() })
        } else block()
    }

    private fun unbind(connection: ServiceConnection, message: String) {
        try {
            RootService.unbind(connection)
        } catch (e: RuntimeException) {
            Logger.me.w(message, e)
        }
    }

    private companion object {
        fun Throwable.asCancellationException() = when (this) {
            is CancellationException -> this
            else -> CancellationException(message).also { it.initCause(this) }
        }
    }
}
