package be.mygod.librootkotlinx

import android.content.Context
import android.os.IBinder
import android.os.Parcelable
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import be.mygod.librootkotlinx.impl.IRootCommandCallback
import be.mygod.librootkotlinx.impl.IRootCommandService
import be.mygod.librootkotlinx.impl.RegisteredRootCommandCallback
import be.mygod.librootkotlinx.impl.RootCommandCallback
import be.mygod.librootkotlinx.impl.RootCommandCallbacks
import be.mygod.librootkotlinx.impl.RootCommandRequest
import be.mygod.librootkotlinx.impl.RootCommandResponse
import be.mygod.librootkotlinx.impl.RootCommandResponseHandling
import be.mygod.librootkotlinx.impl.RootServiceConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RootServer internal constructor() {
    private companion object {
        fun Throwable.asCancellationException() = when (this) {
            is CancellationException -> this
            else -> CancellationException(message).also { it.initCause(this) }
        }
    }
    class UnexpectedExitException : RemoteException("Root service exited unexpectedly")

    val active get() = connected != null && closeCause == null && serverJob.isActive
    private val lifecycleLock = Any()
    @Volatile
    private var closeCause: Throwable? = null
    private var lifecycleStarted = false
    private val serverJob = Job()
    private val serverScope = CoroutineScope(Dispatchers.Default + serverJob)
    // Command submissions and lifecycle callbacks are serialized here. Root responses bypass this queue.
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val started = CompletableDeferred<Unit>()
    private val rootServiceConnected = Job()
    private val commandCallbacks = RootCommandCallbacks()

    @Volatile
    private var connected: RootServiceConnection.Connected? = null
    private var pendingConnection: RootServiceConnection? = null

    private sealed interface Event {
        data class Connected(val service: RootServiceConnection.Connected) : Event
        data class StartupFailed(val connection: RootServiceConnection, val cause: Throwable) : Event
        data class SendCommand(
            val command: Parcelable,
            val factory: (Long) -> RootCommandCallback,
            val result: CompletableDeferred<RegisteredRootCommandCallback>,
        ) : Event
        data class CancelRemote(val id: Long, val callback: RootCommandCallback? = null) : Event
    }

    // Session-scoped callback: multiplex by id to avoid leaking per-command Binder callback refs.
    private val callback = object : IRootCommandCallback.Stub(), IBinder.DeathRecipient {
        override fun onResponse(id: Long, response: RootCommandResponse) {
            handleResponse(id, response)
        }

        override fun binderDied() {
            requestClose(UnexpectedExitException())
        }
    }

    /**
     * Initialize a RootServer by starting the owned root command service.
     *
     * Startup uses the direct provider handoff as its success signal. [handleRootLifecycle] is called only after the
     * service connects; before then, startup stdio and process-exit diagnostics are library-owned. Cancelling
     * initialization revokes ownership of any root process that was started.
     *
     * @param context Any [Context] from the app.
     * @param handleRootLifecycle Handler for the root process and observed stdin/stdout/stderr.
     */
    internal suspend fun init(
        context: Context,
        niceName: String,
        handleRootLifecycle: suspend (
            Process,
            ParcelFileDescriptor,
            ParcelFileDescriptor,
            ParcelFileDescriptor,
        ) -> Unit,
    ) {
        synchronized(lifecycleLock) {
            require(!lifecycleStarted && closeCause == null) { "RootServer is already initialized or closed" }
            lifecycleStarted = true
        }
        // Start immediately so a close racing with init cannot cancel before cleanup is installed.
        serverScope.launch(start = CoroutineStart.UNDISPATCHED) { runLifecycle(context, niceName, handleRootLifecycle) }
        try {
            started.await()
        } catch (e: Throwable) {
            serverJob.cancel(e.asCancellationException())
            withContext(NonCancellable) { serverJob.join() }
            throw e
        }
        Logger.me.d("Root server initialized")
    }

    @Throws(RemoteException::class)
    @DelicateCoroutinesApi
    fun execute(command: RootCommandOneWay) {
        val remote = connectedServiceOrThrow()
        try {
            remote.executeOneWay(RootCommandRequest(command))
        } catch (e: RemoteException) {
            requestClose(e)
            throw e
        }
    }

    @Throws(RemoteException::class)
    suspend inline fun <T : Parcelable?, reified C : RootCommand<T>> execute(command: C) =
        execute(command, C::class.java.classLoader)

    /**
     * Executes [command] in the root service and returns its result.
     *
     * Once command submission is accepted, coroutine cancellation no longer guarantees that the root command has not
     * started. Cancellation waits for the command id and then asks the root service to cancel the command best-effort.
     */
    @Throws(RemoteException::class)
    suspend fun <T : Parcelable?> execute(command: RootCommand<T>, classLoader: ClassLoader?): T {
        val result = CompletableDeferred<T>()
        @Suppress("UNCHECKED_CAST")
        val registered = send(command) {
            RootCommandCallback.Ordinary(classLoader, result as CompletableDeferred<Parcelable?>)
        }
        try {
            return result.await()
        } finally {
            cancelRemote(registered.id, registered.callback)
        }
    }

    /**
     * Creates a cold [Flow] backed by [source] in the root service.
     *
     * Each collection registers a new remote command and starts one root-side [RootFlow.flow] collection. Cancelling the
     * client collector unregisters that command and asks the root service to cancel its job. Root responses are delivered
     * through one-way Binder callbacks, so app-side Flow operators do not backpressure root-side emission. Once command
     * submission is accepted, collector cancellation is best-effort remote cancellation rather than a guarantee that the
     * root flow has not started.
     */
    inline fun <T : Parcelable?, reified C : RootFlow<T>> flow(source: C) = flow(source, C::class.java.classLoader)

    /**
     * Creates a cold [Flow] backed by [source] in the root service.
     *
     * Each collection registers a new remote command and starts one root-side [RootFlow.flow] collection. Cancelling the
     * client collector unregisters that command and asks the root service to cancel its job. Root responses are delivered
     * through one-way Binder callbacks, so app-side Flow operators do not backpressure root-side emission. Once command
     * submission is accepted, collector cancellation is best-effort remote cancellation rather than a guarantee that the
     * root flow has not started.
     */
    fun <T : Parcelable?> flow(source: RootFlow<T>, classLoader: ClassLoader?): Flow<T> = callbackFlow<T> {
        @Suppress("UNCHECKED_CAST")
        val channel = this as SendChannel<Parcelable?>
        val registered = this@RootServer.send(source) {
            RootCommandCallback.Flow(classLoader, channel)
        }
        try {
            awaitClose()
        } finally {
            cancelRemote(registered.id, registered.callback)
        }
    }.buffer(Channel.UNLIMITED)

    /**
     * Close the instance gracefully.
     */
    suspend fun close() {
        val cleanupWithoutLifecycle = synchronized(lifecycleLock) { !lifecycleStarted }
        val cause = closeCause ?: run {
            Logger.me.d("Shutting down from client")
            val closed = CancellationException("Root server closed")
            recordCloseCause(closed)
            closeCause ?: closed
        }
        serverJob.cancel(cause.asCancellationException())
        serverJob.join()
        if (cleanupWithoutLifecycle) cleanup()
    }

    private suspend fun CoroutineScope.runLifecycle(
        context: Context,
        niceName: String,
        handleRootLifecycle: suspend (
            Process,
            ParcelFileDescriptor,
            ParcelFileDescriptor,
            ParcelFileDescriptor,
        ) -> Unit,
    ) {
        try {
            RootServiceConnection(
                context,
                niceName,
                callback,
                handleRootLifecycle,
                canStartRootProcess = { closeCause == null },
                onConnected = { trySendEvent(Event.Connected(it)) },
                onCloseRequested = ::requestClose,
                onStartupFailed = { connection, cause -> trySendEvent(Event.StartupFailed(connection, cause)) },
            ).also {
                pendingConnection = it
                it.bind(this, rootServiceConnected)
            }
            while (closeCause == null) handleEvent(events.receive())
        } catch (e: Throwable) {
            recordCloseCause(e)
        } finally {
            cleanup()
            serverJob.complete()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun handleEvent(event: Event) {
        when (event) {
            is Event.Connected -> handleConnected(event)
            is Event.StartupFailed -> if (connected == null) {
                if (pendingConnection === event.connection) event.connection.markStartupFailed()
                recordCloseCause(event.cause)
            }
            is Event.SendCommand -> sendCommand(event)
            is Event.CancelRemote -> cancelRemoteOwned(event.id, event.callback)?.let(::recordCloseCause)
        }
    }

    private suspend fun handleConnected(event: Event.Connected) {
        val service = event.service
        if (connected == null && closeCause == null) {
            connected = service
            if (pendingConnection === service.connection) pendingConnection = null
            rootServiceConnected.complete()
            started.complete(Unit)
        } else {
            service.close("after cancelled bind")
        }
    }

    private fun handleResponse(id: Long, response: RootCommandResponse) {
        val handling = synchronized(lifecycleLock) {
            if (closeCause == null) commandCallbacks.handleResponse(id, response) else RootCommandResponseHandling.Done
        }
        if (handling == RootCommandResponseHandling.CancelRemote) cancelRemoteSubmitted(id)?.let(::requestClose)
    }

    private fun sendCommand(event: Event.SendCommand) {
        val remote = connected?.service
        if (remote == null) {
            event.result.completeExceptionally(unavailableCause())
            return
        }
        val registered = try {
            commandCallbacks.register(event.factory)
        } catch (e: Throwable) {
            event.result.completeExceptionally(e)
            return
        }
        try {
            remote.execute(registered.id, RootCommandRequest(event.command), callback)
            Logger.me.d("Sent #${registered.id}: ${event.command}")
            event.result.complete(registered)
        } catch (e: Throwable) {
            commandCallbacks.unregister(registered.id, registered.callback)
            registered.callback.close(e)
            event.result.completeExceptionally(e)
            if (e is RemoteException) recordCloseCause(e)
        }
    }

    private fun cancelRemoteOwned(id: Long, commandCallback: RootCommandCallback? = null): RemoteException? {
        if (!commandCallbacks.unregister(id, commandCallback)) return null
        return cancelRemoteSubmitted(id)
    }

    private fun cancelRemoteSubmitted(id: Long): RemoteException? {
        val remote = connected?.service ?: return null
        if (!active) return null
        return try {
            remote.cancel(id)
            null
        } catch (e: RemoteException) {
            e
        }
    }

    private fun connectedServiceOrThrow(): IRootCommandService {
        val remote = connected?.service
        if (remote == null || !active) {
            throw unavailableCause()
        }
        return remote
    }

    // Non-suspending Binder/service callbacks record the terminal state first, then cancel the server lifetime.
    private fun requestClose(cause: Throwable): Boolean {
        if (!recordCloseCause(cause)) return false
        serverJob.cancel(cause.asCancellationException())
        return true
    }

    private fun recordCloseCause(cause: Throwable): Boolean {
        val recorded = synchronized(lifecycleLock) {
            if (closeCause == null) {
                closeCause = cause
                true
            } else false
        }
        if (recorded && cause !is CancellationException) {
            Logger.me.w("Root server closing due to failure: ${cause.message ?: cause.javaClass.name}", cause)
        }
        return recorded
    }

    private suspend fun cleanup() {
        val cause = closeCause ?: CancellationException("Root server closed")
        val connected = connected
        val pendingConnection = pendingConnection
        recordCloseCause(cause)
        if (cause !is CancellationException) Logger.me.w("Root server close cause", cause)
        rootServiceConnected.cancel(cause.asCancellationException())
        events.close(cause)
        if (!started.isCompleted) {
            if (cause is CancellationException) started.cancel(cause)
            else started.completeExceptionally(cause)
        }
        val connection = connected?.connection ?: pendingConnection
        withContext(NonCancellable) {
            while (true) {
                when (val event = events.tryReceive().getOrNull() ?: break) {
                    is Event.SendCommand -> event.result.completeExceptionally(cause)
                    else -> Unit
                }
            }
            commandCallbacks.closeAll(cause)
            connection?.close(cause.asCancellationException(), connected)
            this@RootServer.connected = null
            this@RootServer.pendingConnection = null
        }
    }

    private suspend fun send(
        command: Parcelable,
        factory: (Long) -> RootCommandCallback,
    ): RegisteredRootCommandCallback {
        val result = CompletableDeferred<RegisteredRootCommandCallback>()
        if (!trySendClientEvent(Event.SendCommand(command, factory, result))) throw unavailableCause()
        // After the event is accepted, cancellation must wait for the id needed to cancel remote work.
        return withContext(NonCancellable) { result.await() }
    }

    private fun cancelRemote(id: Long, commandCallback: RootCommandCallback? = null) {
        trySendEvent(Event.CancelRemote(id, commandCallback))
    }

    private fun trySendClientEvent(event: Event) = active && trySendEvent(event)

    private fun trySendEvent(event: Event) = events.trySend(event).isSuccess

    private fun unavailableCause() = closeCause ?: UnexpectedExitException()
}
