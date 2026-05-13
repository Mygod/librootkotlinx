@file:JvmName("FileDescriptorChannels")
@file:JvmMultifileClass

package be.mygod.librootkotlinx.io

import android.os.Handler
import android.os.MessageQueue
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Write channel backed by a [FileDescriptor] registered on a [MessageQueue].
 */
internal abstract class FileDescriptorWriteChannel(
    private val fileDescriptor: FileDescriptor,
    handler: Handler,
    private val buffer: ByteArray,
    private val channel: ByteChannel = ByteChannel(),
) : ByteWriteChannel by channel {
    private val defaultEventAwaiter = lazy { FileDescriptorEventAwaiter(fileDescriptor, handler.looper.queue) }
    private val closed = AtomicBoolean()
    private val drainJob: Deferred<Unit>

    init {
        fileDescriptor.isNonblocking = true
        drainJob = CoroutineScope(handler.asCoroutineDispatcher("pfd-writer")).async {
            var failure: Throwable? = null
            try {
                while (true) {
                    val count = channel.readAvailable(buffer)
                    if (count < 0) break
                    var offset = 0
                    while (offset < count) {
                        val written = try {
                            Os.write(fileDescriptor, buffer, offset, count - offset)
                        } catch (e: ErrnoException) {
                            when (e.errno) {
                                OsConstants.EAGAIN -> {
                                    awaitEvent(MessageQueue.OnFileDescriptorEventListener.EVENT_OUTPUT)
                                    continue
                                }
                                OsConstants.EINTR -> continue
                                else -> throw e
                            }
                        }
                        if (written > 0) {
                            offset += written
                        } else {
                            awaitEvent(MessageQueue.OnFileDescriptorEventListener.EVENT_OUTPUT)
                        }
                    }
                }
            } catch (e: CancellationException) {
                failure = e
                throw e
            } catch (e: Exception) {
                failure = e
                channel.cancel(e)
                throw e
            } finally {
                closeDescriptorOnce()?.let { closeError ->
                    failure?.addSuppressed(closeError) ?: throw closeError
                }
            }
        }
    }

    override fun cancel(cause: Throwable?) {
        val closeError = closeDescriptorOnce()
        if (cause != null && closeError != null) cause.addSuppressed(closeError)
        channel.cancel(cause ?: closeError)
        drainJob.cancel()
    }

    override suspend fun flushAndClose() {
        try {
            channel.flushAndClose()
            drainJob.await()
        } catch (e: CancellationException) {
            cancel(e)
            throw e
        } catch (e: Exception) {
            cancel(e)
            throw e
        }
    }

    private fun closeDescriptorOnce(): IOException? {
        if (!closed.compareAndSet(false, true)) return null
        closeEvents()
        return try {
            closeDescriptor()
            null
        } catch (e: IOException) {
            e
        }
    }

    protected open val eventAwaiter: FileDescriptorEventAwaiter
        get() = defaultEventAwaiter.value

    protected open suspend fun awaitEvent(events: Int) = eventAwaiter.await(events)

    protected open fun closeEvents() {
        if (defaultEventAwaiter.isInitialized()) defaultEventAwaiter.value.close()
    }

    protected abstract fun closeDescriptor()
}

/**
 * Opens a write channel that owns this entire [ParcelFileDescriptor].
 *
 * The descriptor is registered on [handler]'s [MessageQueue].
 *
 * Closing or cancelling the returned channel closes the descriptor. This API does not provide socket-style half shutdown.
 */
fun ParcelFileDescriptor.openWriteChannel(
    handler: Handler,
    buffer: ByteArray = ByteArray(DEFAULT_BUFFER_SIZE),
): ByteWriteChannel = object : FileDescriptorWriteChannel(fileDescriptor, handler, buffer) {
    override fun closeDescriptor() = close()
}

/**
 * Opens a write channel that owns this [FileDescriptor].
 *
 * The descriptor is registered on [handler]'s [MessageQueue].
 *
 * Closing or cancelling the returned channel closes the descriptor. This API does not provide socket-style half shutdown.
 */
fun FileDescriptor.openWriteChannel(
    handler: Handler,
    buffer: ByteArray = ByteArray(DEFAULT_BUFFER_SIZE),
): ByteWriteChannel = object : FileDescriptorWriteChannel(this, handler, buffer) {
    override fun closeDescriptor() {
        try {
            Os.close(this@openWriteChannel)
        } catch (e: ErrnoException) {
            throw IOException(e)
        }
    }
}
