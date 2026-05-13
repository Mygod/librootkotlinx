package be.mygod.librootkotlinx.impl

import android.os.Parcelable
import androidx.collection.MutableLongObjectMap
import be.mygod.librootkotlinx.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.SendChannel

internal sealed interface RootCommandCallbackAction {
    data object Keep : RootCommandCallbackAction
    data object Remove : RootCommandCallbackAction
    data object CancelRemote : RootCommandCallbackAction
}

internal sealed class RootCommandCallback(
    protected val classLoader: ClassLoader?,
) {
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

        override fun invoke(response: RootCommandResponse): RootCommandCallbackAction {
            if (response.status == RootCommandResponse.SUCCESS) {
                result.complete(response.payload)
            } else {
                result.completeExceptionally(response.readException(classLoader))
            }
            return RootCommandCallbackAction.Remove
        }
    }

    class Flow(
        classLoader: ClassLoader?,
        private val channel: SendChannel<Parcelable?>,
    ) : RootCommandCallback(classLoader) {
        override fun close(cause: Throwable) {
            channel.close(cause)
        }

        override fun invoke(response: RootCommandResponse): RootCommandCallbackAction {
            when (response.status) {
                RootCommandResponse.SUCCESS -> {
                    val result = channel.trySend(response.payload)
                    if (result.isClosed) {
                        return RootCommandCallbackAction.CancelRemote
                    } else if (result.isFailure) {
                        val cause = result.exceptionOrNull()
                                ?: IllegalStateException("Flow buffer rejected root command response")
                        channel.close(cause)
                        return RootCommandCallbackAction.CancelRemote
                    }
                }
                RootCommandResponse.COMPLETE -> {
                    channel.close()
                    return RootCommandCallbackAction.Remove
                }
                else -> {
                    channel.close(response.readException(classLoader))
                    return RootCommandCallbackAction.Remove
                }
            }
            return RootCommandCallbackAction.Keep
        }
    }
}

internal data class RegisteredRootCommandCallback(
    val id: Long,
    val callback: RootCommandCallback,
)

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

    fun handleResponse(
        id: Long,
        response: RootCommandResponse,
    ): RootCommandResponseHandling {
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
                } else {
                    RootCommandResponseHandling.Done
                }
            }
        } catch (e: Throwable) {
            unregister(id, callback)
            callback.close(e)
            Logger.me.w("Failed to handle root command response #$id", e)
            RootCommandResponseHandling.Done
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
