package be.mygod.librootkotlinx.impl

import android.os.Parcelable
import androidx.collection.MutableLongObjectMap
import be.mygod.librootkotlinx.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.SendChannel

internal enum class RootCommandCallbackAction {
    Keep,
    Remove,
    CancelRemote,
}

internal sealed class RootCommandCallback(protected val classLoader: ClassLoader?) {
    abstract fun close(cause: Throwable)
    abstract operator fun invoke(response: RootCommandResponse): RootCommandCallbackAction

    class Ordinary(
        classLoader: ClassLoader?,
        private val result: CompletableDeferred<Parcelable?>,
    ) : RootCommandCallback(classLoader) {
        override fun close(cause: Throwable) {
            if (cause is CancellationException) result.cancel(cause)
            else result.completeExceptionally(cause)
        }

        override fun invoke(response: RootCommandResponse) = RootCommandCallbackAction.Remove.also {
            if (response.status == RootCommandResponse.SUCCESS) result.complete(response.readPayload(classLoader))
            else result.completeExceptionally(response.readException(classLoader))
        }
    }

    class Flow(
        classLoader: ClassLoader?,
        private val channel: SendChannel<Parcelable?>,
    ) : RootCommandCallback(classLoader) {
        override fun close(cause: Throwable) {
            channel.close(cause)
        }

        override fun invoke(response: RootCommandResponse) = when (response.status) {
            RootCommandResponse.SUCCESS -> {
                val result = channel.trySend(response.readPayload(classLoader))
                when {
                    result.isClosed -> RootCommandCallbackAction.CancelRemote
                    result.isFailure -> RootCommandCallbackAction.CancelRemote.also {
                        val cause = result.exceptionOrNull()
                            ?: IllegalStateException("Flow buffer rejected root command response")
                        channel.close(cause)
                    }
                    else -> RootCommandCallbackAction.Keep
                }
            }
            RootCommandResponse.COMPLETE -> RootCommandCallbackAction.Remove.also { channel.close() }
            else -> RootCommandCallbackAction.Remove.also { channel.close(response.readException(classLoader)) }
        }
    }
}

internal data class RegisteredRootCommandCallback(val id: Long, val callback: RootCommandCallback)

internal enum class RootCommandResponseHandling {
    Done,
    CancelRemote,
}

internal class RootCommandCallbacks {
    private val callbacks = MutableLongObjectMap<RootCommandCallback>()
    private var counter = 0L

    fun register(factory: (Long) -> RootCommandCallback): RegisteredRootCommandCallback = synchronized(this) {
        val id = counter++
        RegisteredRootCommandCallback(id, factory(id)).also {
            callbacks[id] = it.callback
        }
    }

    fun unregister(id: Long, callback: RootCommandCallback? = null): Boolean = synchronized(this) {
        if (callback == null) callbacks.remove(id) != null else callbacks.remove(id, callback)
    }

    fun handleResponse(id: Long, response: RootCommandResponse): RootCommandResponseHandling {
        val callback = synchronized(this) { callbacks[id] } ?: return RootCommandResponseHandling.Done
        return try {
            when (callback(response)) {
                RootCommandCallbackAction.Keep -> RootCommandResponseHandling.Done
                RootCommandCallbackAction.Remove -> {
                    unregister(id, callback)
                    RootCommandResponseHandling.Done
                }
                RootCommandCallbackAction.CancelRemote -> if (unregister(id, callback)) {
                    RootCommandResponseHandling.CancelRemote
                } else RootCommandResponseHandling.Done
            }
        } catch (e: Throwable) {
            val removed = unregister(id, callback)
            callback.close(e)
            Logger.me.w("Failed to handle root command response #$id", e)
            if (removed && callback is RootCommandCallback.Flow) {
                RootCommandResponseHandling.CancelRemote
            } else RootCommandResponseHandling.Done
        }
    }

    fun closeAll(cause: Throwable) {
        val pending = ArrayList<RootCommandCallback>()
        synchronized(this) {
            callbacks.forEachValue { pending.add(it) }
            callbacks.clear()
        }
        pending.forEach { it.close(cause) }
    }
}
