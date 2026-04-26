package be.mygod.librootkotlinx.impl

import android.content.Intent
import android.os.IBinder
import android.os.Parcelable
import android.os.RemoteException
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.ParcelableThrowable
import be.mygod.librootkotlinx.RootCommand
import be.mygod.librootkotlinx.RootCommandChannel
import be.mygod.librootkotlinx.RootCommandOneWay
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RootCommandService : RootService() {
    private data class CommandKey(val callback: IBinder, val id: Long)

    private val serviceJob = SupervisorJob()
    private val commandScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private val callbackDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val cancellables = HashMap<CommandKey, Job>()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    private val binder = object : IRootCommandService.Stub() {
        override fun execute(id: Long, request: RootCommandRequest, callback: IRootCommandCallback) {
            when (val command = request.command) {
                is RootCommand<*> -> executeCommand(id, command, callback)
                is RootCommandChannel<*> -> executeChannel(id, command, callback)
                else -> commandScope.launch {
                    callback.trySendThrowable(id, IllegalArgumentException("Unrecognized input: $command"))
                }
            }
        }

        override fun executeOneWay(request: RootCommandRequest) {
            val command = request.command
            commandScope.launch {
                try {
                    (command as RootCommandOneWay).execute()
                } catch (e: Throwable) {
                    Logger.me.e("Unexpected exception in RootCommandOneWay (${command.javaClass.simpleName})", e)
                }
            }
        }

        override fun cancel(id: Long, callback: IRootCommandCallback) {
            synchronized(this@RootCommandService) { cancellables[CommandKey(callback.asBinder(), id)] }?.cancel()
        }

        override fun close(callback: IRootCommandCallback) = cancel(callback.asBinder())
    }

    private fun executeCommand(id: Long, command: RootCommand<*>, callback: IRootCommandCallback) {
        val commandKey = CommandKey(callback.asBinder(), id)
        val commandJob = Job(serviceJob)
        synchronized(this) { cancellables[commandKey] = commandJob }
        commandScope.launch(commandJob) {
            try {
                callback.sendResponse(id, RootCommandResponse(RootCommandResponse.SUCCESS, command.execute()))
            } catch (e: Throwable) {
                if (e is CancellationException && !currentCoroutineContext().isActive) return@launch
                callback.trySendThrowable(id, e)
            } finally {
                synchronized(this@RootCommandService) { cancellables.remove(commandKey) }
            }
        }
    }

    private fun executeChannel(id: Long, command: RootCommandChannel<*>, callback: IRootCommandCallback) {
        val commandKey = CommandKey(callback.asBinder(), id)
        val commandJob = Job(serviceJob)
        synchronized(this) { cancellables[commandKey] = commandJob }
        commandScope.launch(commandJob) {
            try {
                coroutineScope {
                    command.create(this).consumeEach { result ->
                        callback.sendResponse(id, RootCommandResponse(RootCommandResponse.SUCCESS, result))
                    }
                }
                callback.sendResponse(id, RootCommandResponse(RootCommandResponse.CHANNEL_CONSUMED, null))
            } catch (e: Throwable) {
                if (e is CancellationException && !currentCoroutineContext().isActive) return@launch
                callback.trySendThrowable(id, e)
            } finally {
                synchronized(this@RootCommandService) { cancellables.remove(commandKey) }
            }
        }
    }

    private fun cancel(callback: IBinder) {
        val jobs = synchronized(this) {
            cancellables.entries.asSequence().filter { it.key.callback == callback }.map { it.value }.toList()
        }
        for (job in jobs) job.cancel()
    }

    private suspend fun IRootCommandCallback.trySendThrowable(id: Long, throwable: Throwable) {
        try {
            sendResponse(id, if (throwable is Parcelable) {
                RootCommandResponse(RootCommandResponse.EX_PARCELABLE, throwable)
            } else {
                RootCommandResponse(RootCommandResponse.EX_THROWABLE, ParcelableThrowable(throwable))
            })
        } catch (e: RemoteException) {
            Logger.me.d("Failed to deliver root command failure #$id", e)
            cancel(asBinder())
        }
    }

    private suspend fun IRootCommandCallback.sendResponse(id: Long, response: RootCommandResponse) {
        withContext(callbackDispatcher + NonCancellable) {
            onResponse(id, response)
        }
    }
}
