package be.mygod.librootkotlinx.impl

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcelable
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.ParcelableThrowable
import be.mygod.librootkotlinx.RootCommand
import be.mygod.librootkotlinx.RootCommandOneWay
import be.mygod.librootkotlinx.RootFlow
import be.mygod.librootkotlinx.systemContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RootCommandService(context: Context? = null) {
    private val serviceJob = SupervisorJob()
    private val commandScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private val callbackDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val commandJobs = RootCommandJobs()

    init {
        if (context != null) systemContext = context
    }

    fun asBinder(): IBinder = binder

    private val binder = object : IRootCommandService.Stub() {
        override fun execute(id: Long, request: RootCommandRequest, callback: IRootCommandCallback) {
            when (val command = request.command) {
                is RootCommand<*> -> launchCancellable(id, callback) {
                    callback.sendResponse(id, RootCommandResponse.success(command.execute()))
                }
                is RootFlow<*> -> launchCancellable(id, callback) {
                    command.flow().collect { result ->
                        callback.sendResponse(id, RootCommandResponse.success(result))
                    }
                    callback.sendResponse(id, RootCommandResponse.complete)
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

        override fun cancel(id: Long) {
            commandJobs.cancel(id)
        }

        override fun close() = commandJobs.cancelAll()
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
        commandJobs.track(id, commandJob)
        commandJob.start()
    }

    private suspend fun IRootCommandCallback.trySendThrowable(id: Long, throwable: Throwable) {
        try {
            sendResponse(id, if (throwable is Parcelable) {
                RootCommandResponse.parcelableFailure(throwable)
            } else {
                RootCommandResponse.failure(ParcelableThrowable(throwable))
            })
        } catch (e: Throwable) {
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            Logger.me.w("Failed to deliver root command failure #$id", e)
            try {
                sendResponse(id, RootCommandResponse.failure(
                    ParcelableThrowable.Other(
                        "java.lang.IllegalStateException: Root command failed with ${throwable.javaClass.name}, " +
                            "but its failure payload could not be delivered",
                    ),
                ))
            } catch (fallbackFailure: Throwable) {
                if (fallbackFailure is CancellationException && !currentCoroutineContext().isActive) {
                    throw fallbackFailure
                }
                Logger.me.w("Failed to deliver fallback root command failure #$id; stopping root service", fallbackFailure)
                commandJobs.cancelAll()
                serviceJob.cancel()
            }
        }
    }

    private suspend fun IRootCommandCallback.sendResponse(id: Long, response: RootCommandResponse) {
        withContext(callbackDispatcher) { onResponse(id, response) }
    }
}
