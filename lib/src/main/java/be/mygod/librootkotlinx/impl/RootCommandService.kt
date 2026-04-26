package be.mygod.librootkotlinx.impl

import android.content.Intent
import android.os.IBinder
import android.os.Parcelable
import android.os.RemoteException
import androidx.collection.LongSparseArray
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
    private val serviceJob = SupervisorJob()
    private val commandScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private val callbackDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val cancellables = LongSparseArray<Job>()

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
                    callback.sendThrowable(id, IllegalArgumentException("Unrecognized input: $command"))
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

        override fun cancel(id: Long) {
            synchronized(this@RootCommandService) { cancellables[id] }?.cancel()
        }

        override fun close() {
            serviceJob.cancel()
            stopSelf()
        }
    }

    private fun executeCommand(id: Long, command: RootCommand<*>, callback: IRootCommandCallback) {
        val commandJob = Job(serviceJob)
        synchronized(this) { cancellables.put(id, commandJob) }
        commandScope.launch(commandJob) {
            try {
                callback.sendResponse(id, RootCommandResponse(RootCommandResponse.SUCCESS, command.execute()))
            } catch (e: Throwable) {
                if (e is CancellationException && !currentCoroutineContext().isActive) return@launch
                callback.sendThrowable(id, e)
            } finally {
                synchronized(this@RootCommandService) { cancellables.remove(id) }
            }
        }
    }

    private fun executeChannel(id: Long, command: RootCommandChannel<*>, callback: IRootCommandCallback) {
        val commandJob = Job(serviceJob)
        synchronized(this) { cancellables.put(id, commandJob) }
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
                callback.sendThrowable(id, e)
            } finally {
                synchronized(this@RootCommandService) { cancellables.remove(id) }
            }
        }
    }

    private suspend fun IRootCommandCallback.sendThrowable(id: Long, throwable: Throwable) {
        sendResponse(id, if (throwable is Parcelable) {
            RootCommandResponse(RootCommandResponse.EX_PARCELABLE, throwable)
        } else {
            RootCommandResponse(RootCommandResponse.EX_THROWABLE, ParcelableThrowable(throwable))
        })
    }

    private suspend fun IRootCommandCallback.sendResponse(id: Long, response: RootCommandResponse) {
        try {
            withContext(callbackDispatcher + NonCancellable) {
                onResponse(id, response)
            }
        } catch (e: RemoteException) {
            Logger.me.d("Failed to deliver root command response #$id", e)
        }
    }
}
