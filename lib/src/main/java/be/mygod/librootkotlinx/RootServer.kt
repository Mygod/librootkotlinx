package be.mygod.librootkotlinx

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcelable
import android.os.RemoteException
import androidx.collection.LongSparseArray
import androidx.collection.valueIterator
import be.mygod.librootkotlinx.impl.IRootCommandCallback
import be.mygod.librootkotlinx.impl.IRootCommandService
import be.mygod.librootkotlinx.impl.PendingRootServiceBind
import be.mygod.librootkotlinx.impl.RootCommandRequest
import be.mygod.librootkotlinx.impl.RootCommandResponse
import be.mygod.librootkotlinx.impl.RootCommandService
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RootServer internal constructor() {
    private sealed class Callback(
        protected val server: RootServer,
        val index: Long,
        protected val classLoader: ClassLoader?,
    ) {
        var active = true

        abstract fun cancel(e: CancellationException? = null)
        abstract fun shouldRemove(status: Int): Boolean
        abstract operator fun invoke(response: RootCommandResponse)

        class Ordinary(
            server: RootServer,
            index: Long,
            classLoader: ClassLoader?,
            private val result: CompletableDeferred<Parcelable?>,
        ) : Callback(server, index, classLoader) {
            override fun cancel(e: CancellationException?) = result.cancel(e)
            override fun shouldRemove(status: Int) = true
            override fun invoke(response: RootCommandResponse) {
                if (response.status == RootCommandResponse.SUCCESS) {
                    result.complete(response.payload)
                } else {
                    result.completeExceptionally(response.readException(classLoader))
                }
            }
        }

        class Channel(
            server: RootServer,
            index: Long,
            classLoader: ClassLoader?,
            private val channel: SendChannel<Parcelable?>,
        ) : Callback(server, index, classLoader) {
            val finish = CompletableDeferred<Unit>()

            override fun cancel(e: CancellationException?) = finish.cancel(e)
            override fun shouldRemove(status: Int) = status != RootCommandResponse.SUCCESS
            override fun invoke(response: RootCommandResponse) {
                when (response.status) {
                    RootCommandResponse.SUCCESS -> {
                        val result = channel.trySend(response.payload)
                        if (result.isClosed) {
                            server.cancelRemote(index, this)
                            finish.completeExceptionally(result.exceptionOrNull()
                                    ?: ClosedSendChannelException("Channel was closed normally"))
                        } else result.exceptionOrNull()?.let { throw it }
                    }
                    RootCommandResponse.CHANNEL_CONSUMED -> finish.complete(Unit)
                    else -> finish.completeExceptionally(response.readException(classLoader))
                }
            }
        }
    }

    private data class RegisteredCallback(val id: Long, val callback: Callback, val service: IRootCommandService)

    class UnexpectedExitException : RemoteException("Root service exited unexpectedly")

    @Volatile
    var active = false
        private set

    private val callbackLookup = LongSparseArray<Callback>()
    private var counter = 0L
    private var service: IRootCommandService? = null
    private var serviceConnection: ServiceConnection? = null
    private var serviceBinder: IBinder? = null

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
                callback.cancel(e.asCancellationException())
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
     * @param context Any [Context] from the app.
     */
    suspend fun init(context: Context) {
        synchronized(callbackLookup) { require(!active) { "RootServer is already active" } }
        withContext(Dispatchers.Main.immediate) { bind(context) }
        Logger.me.d("Root server initialized")
    }
    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun bind(context: Context) = suspendCancellableCoroutine { continuation ->
        var resumed = false
        var pendingBind: PendingRootServiceBind? = null
        val connection = object : ServiceConnection {
            fun resumeWithFailure(throwable: Throwable, clearEnRoute: Boolean = false) {
                val connection = this
                GlobalScope.launch(Dispatchers.Main.immediate) {
                    val (shouldResume, shouldClose) = synchronized(callbackLookup) {
                        when {
                            !resumed && continuation.isActive -> {
                                resumed = true
                                true to false
                            }
                            resumed -> false to (serviceConnection === connection)
                            else -> false to false
                        }
                    }
                    if (clearEnRoute) {
                        try {
                            pendingBind?.cancel()
                        } catch (e: Throwable) {
                            if (shouldResume) throwable.addSuppressed(e)
                            else Logger.me.d("Failed to clean up libsu pending RootService bind after bind failure", e)
                        }
                    }
                    if (shouldResume) {
                        unbind(connection, "Failed to unbind root service connection after bind failure")
                        continuation.resumeWithException(throwable)
                    } else if (shouldClose) closeInternal(throwable)
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
                    if (resumed || !continuation.isActive) false else {
                        resumed = true
                        service = remote
                        serviceConnection = this
                        serviceBinder = binder
                        active = true
                        true
                    }
                }
                if (shouldResume) continuation.resume(Unit) else {
                    try {
                        binder.unlinkToDeath(callback, 0)
                    } catch (e: RuntimeException) {
                        Logger.me.d("Failed to unlink root service death recipient after cancelled bind", e)
                    }
                    try {
                        RootService.unbind(this)
                    } catch (e: RuntimeException) {
                        Logger.me.d("Failed to unbind root service connection after cancelled bind", e)
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
            GlobalScope.launch(Dispatchers.Main.immediate) {
                closeInternal(cause)
                unbind(connection, "Failed to unbind root service connection after coroutine cancellation")
            }
        }

        val intent = Intent(context, RootCommandService::class.java)
        val pending = try {
            PendingRootServiceBind(intent, connection)
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
            )
        } catch (e: Throwable) {
            connection.resumeWithFailure(e, clearEnRoute = pending.ownsStartupIfQueued)
            null
        }
        if (task != null) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val shell = Shell.getShell()
                    if (shell.isRoot) shell.execTask(task) else {
                        throw NoShellException("Root shell is not available")
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) return@launch
                    connection.resumeWithFailure(e, clearEnRoute = true)
                }
            }
        }
    }

    fun execute(command: RootCommandOneWay) {
        val remote = synchronized(callbackLookup) {
            if (active) service else null
        } ?: return
        try {
            remote.executeOneWay(RootCommandRequest(command))
        } catch (e: RemoteException) {
            closeAfterServiceCallFailure(e)
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
        val registered = registerCallback {
            Callback.Ordinary(this, it, classLoader, result as CompletableDeferred<Parcelable?>)
        } ?: run {
            result.cancel()
            return result.await()
        }
        send(registered, command)
        try {
            return result.await()
        } finally {
            if (unregisterIfActive(registered)) cancelRemote(registered.id)
        }
    }

    @ExperimentalCoroutinesApi
    @Throws(RemoteException::class)
    inline fun <T : Parcelable?, reified C : RootCommandChannel<T>> create(command: C, scope: CoroutineScope) =
        create(command, scope, C::class.java.classLoader)
    @ExperimentalCoroutinesApi
    @Throws(RemoteException::class)
    fun <T : Parcelable?> create(command: RootCommandChannel<T>, scope: CoroutineScope, classLoader: ClassLoader?) =
        scope.produce<T>(SupervisorJob(), command.capacity.also {
            when (it) {
                Channel.UNLIMITED, Channel.CONFLATED -> { }
                else -> throw IllegalArgumentException("Unsupported channel capacity $it")
            }
        }) {
            @Suppress("UNCHECKED_CAST")
            val registered = registerCallback {
                Callback.Channel(this@RootServer, it, classLoader, this as SendChannel<Parcelable?>)
            } ?: return@produce
            send(registered, command)
            try {
                (registered.callback as Callback.Channel).finish.await()
            } finally {
                if (unregisterIfActive(registered)) cancelRemote(registered.id)
            }
        }

    private fun registerCallback(factory: (Long) -> Callback) = synchronized(callbackLookup) {
        val remote = service
        if (!active || remote == null) return@synchronized null
        val id = counter++
        RegisteredCallback(id, factory(id).also { callbackLookup.put(id, it) }, remote)
    }

    private fun send(registered: RegisteredCallback, command: Parcelable) {
        try {
            registered.service.execute(registered.id, RootCommandRequest(command), callback)
            Logger.me.d("Sent #${registered.id}: $command")
        } catch (e: RemoteException) {
            synchronized(callbackLookup) {
                callbackLookup.remove(registered.id)
                registered.callback.active = false
            }
            registered.callback.cancel(e.asCancellationException())
            closeAfterServiceCallFailure(e)
            throw e
        }
    }

    private fun unregisterIfActive(registered: RegisteredCallback) = synchronized(callbackLookup) {
        if (!registered.callback.active) return@synchronized false
        registered.callback.active = false
        callbackLookup.remove(registered.id)
        true
    }

    private fun cancelRemote(id: Long, commandCallback: Callback? = null) {
        val remote = synchronized(callbackLookup) {
            if (commandCallback != null && callbackLookup[id] === commandCallback) {
                callbackLookup.remove(id)
                commandCallback.active = false
            }
            if (active) service else null
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
        connection?.let { unbindAsync(it, "Root service unbind after Binder failure failed") }
    }

    private fun closeInternal(cause: Throwable? = null): Pair<IRootCommandService?, ServiceConnection?> {
        val pendingCallbacks = ArrayList<Callback>()
        val binder: IBinder?
        val snapshot = synchronized(callbackLookup) {
            val snapshot = service to serviceConnection
            binder = serviceBinder
            if (!active && snapshot.first == null && snapshot.second == null) return@synchronized snapshot
            active = false
            service = null
            serviceConnection = null
            serviceBinder = null
            for (callback in callbackLookup.valueIterator()) pendingCallbacks.add(callback)
            callbackLookup.clear()
            snapshot
        }
        try {
            binder?.unlinkToDeath(callback, 0)
        } catch (e: RuntimeException) {
            Logger.me.d("Failed to unlink root service death recipient", e)
        }
        val cancellation = cause.asCancellationException()
        for (callback in pendingCallbacks) {
            callback.active = false
            callback.cancel(cancellation)
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
            withContext(Dispatchers.Main.immediate) {
                unbind(it, "Root service unbind failed")
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun unbindAsync(connection: ServiceConnection, message: String) {
        GlobalScope.launch(Dispatchers.Main.immediate) { unbind(connection, message) }
    }

    private fun unbind(connection: ServiceConnection, message: String) {
        try {
            RootService.unbind(connection)
        } catch (e: RuntimeException) {
            Logger.me.d(message, e)
        }
    }

    companion object {
        private fun Throwable?.asCancellationException() = when (this) {
            null -> CancellationException()
            is CancellationException -> this
            else -> CancellationException(message).also { it.initCause(this) }
        }
    }
}
