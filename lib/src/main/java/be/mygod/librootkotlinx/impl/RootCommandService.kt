package be.mygod.librootkotlinx.impl

import android.content.Intent
import android.os.IBinder
import android.os.Parcelable
import android.os.RemoteException
import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableObjectList
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.ParcelableThrowable
import be.mygod.librootkotlinx.RootCommand
import be.mygod.librootkotlinx.RootCommandOneWay
import be.mygod.librootkotlinx.RootFlow
import be.mygod.librootkotlinx.systemContext
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RootCommandService : RootService() {
    private val serviceJob = SupervisorJob()
    private val commandScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private val callbackDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val cancellables = MutableLongObjectMap<Job>()

    override fun onBind(intent: Intent): IBinder {
        systemContext = this
        return binder
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    private val binder = object : IRootCommandService.Stub() {
        override fun execute(id: Long, request: RootCommandRequest, callback: IRootCommandCallback) {
            when (val command = request.command) {
                is RootCommand<*> -> launchCancellable(id, callback) {
                    callback.sendResponse(id, RootCommandResponse(RootCommandResponse.SUCCESS, command.execute()))
                }
                is RootFlow<*> -> launchCancellable(id, callback) {
                    command.flow().collect { result ->
                        callback.sendResponse(id, RootCommandResponse(RootCommandResponse.SUCCESS, result))
                    }
                    callback.sendResponse(id, RootCommandResponse(RootCommandResponse.COMPLETE, null))
                }
                else -> commandScope.launch {
                    callback.trySendThrowable(id, IllegalArgumentException("Unrecognized input: $command"))
                }
            }
        }

        @OptIn(DelicateCoroutinesApi::class)
        override fun executeOneWay(request: RootCommandRequest) {
            val command = request.command
            commandScope.launch {
                try {
                    (command as RootCommandOneWay).execute()
                } catch (e: Throwable) {
                    if (e is CancellationException && !currentCoroutineContext().isActive) return@launch
                    Logger.me.e("Unexpected exception in RootCommandOneWay (${command.javaClass.simpleName})", e)
                }
            }
        }

        override fun cancel(id: Long, callback: IRootCommandCallback) {
            synchronized(this@RootCommandService) { cancellables[id] }?.cancel()
        }

        override fun close(callback: IRootCommandCallback) = cancelAll()
    }

    private fun launchCancellable(id: Long, callback: IRootCommandCallback, block: suspend CoroutineScope.() -> Unit) {
        val commandJob = commandScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: Throwable) {
                if (e is CancellationException && !currentCoroutineContext().isActive) return@launch
                callback.trySendThrowable(id, e)
            }
        }
        synchronized(this) { cancellables[id] = commandJob }
        commandJob.invokeOnCompletion {
            synchronized(this@RootCommandService) { cancellables.remove(id, commandJob) }
        }
        commandJob.start()
    }

    private fun cancelAll() {
        val jobs = MutableObjectList<Job>()
        synchronized(this) { cancellables.forEachValue { jobs.add(it) } }
        jobs.forEach { it.cancel() }
    }

    private suspend fun IRootCommandCallback.trySendThrowable(id: Long, throwable: Throwable) {
        try {
            sendResponse(id, if (throwable is Parcelable) {
                RootCommandResponse(RootCommandResponse.EX_PARCELABLE, throwable)
            } else {
                RootCommandResponse(RootCommandResponse.EX_THROWABLE, ParcelableThrowable(throwable))
            })
        } catch (e: RemoteException) {
            Logger.me.w("Failed to deliver root command failure #$id", e)
            cancelAll()
        }
    }

    private suspend fun IRootCommandCallback.sendResponse(id: Long, response: RootCommandResponse) {
        withContext(callbackDispatcher) { onResponse(id, response) }
    }
}
