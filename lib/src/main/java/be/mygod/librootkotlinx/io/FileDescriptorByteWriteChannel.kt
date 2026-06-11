@file:JvmName("FileDescriptorChannels")
@file:JvmMultifileClass

package be.mygod.librootkotlinx.io

import android.os.Handler
import android.os.MessageQueue
import android.os.ParcelFileDescriptor
import android.os.StrictMode
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
    private val closed = AtomicBoolean()
    private val drainJob: Deferred<Unit>
    protected abstract val eventAwaiter: FileDescriptorEventAwaiter

    init {
        fileDescriptor.isNonblocking = true
        drainJob = CoroutineScope(handler.asCoroutineDispatcher("pfd-writer")).async {
            try {
                while (true) {
                    val count = channel.readAvailable(buffer)
                    if (count < 0) break
                    var offset = 0
                    while (offset < count) {
                        val written = try {
                            // BlockGuardOs reports all Os.write calls as disk writes; this fd is nonblocking.
                            val policy = StrictMode.allowThreadDiskWrites()
                            try {
                                Os.write(fileDescriptor, buffer, offset, count - offset)
                            } finally {
                                StrictMode.setThreadPolicy(policy)
                            }
                        } catch (e: ErrnoException) {
                            when (e.errno) {
                                OsConstants.EAGAIN -> {
                                    eventAwaiter.await(input = false)
                                    continue
                                }
                                OsConstants.EINTR -> continue
                                else -> throw e
                            }
                        }
                        if (written > 0) {
                            offset += written
                        } else {
                            eventAwaiter.await(input = false)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                channel.cancel(e)
                throw e
            } finally {
                closeDescriptorOnce()
            }
        }
    }

    override fun cancel(cause: Throwable?) {
        closeDescriptorOnce()
        channel.cancel(cause)
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

    private fun closeDescriptorOnce() {
        if (!closed.compareAndSet(false, true)) return
        closeEvents()
        closeDescriptor()
    }

    protected open fun closeEvents() = eventAwaiter.close()
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
): ByteWriteChannel {
    val awaiter = FileDescriptorEventAwaiter(fileDescriptor, handler.looper.queue)
    return object : FileDescriptorWriteChannel(fileDescriptor, handler, buffer) {
        override val eventAwaiter get() = awaiter
        override fun closeDescriptor() = close()
    }
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
): ByteWriteChannel {
    val awaiter = FileDescriptorEventAwaiter(this, handler.looper.queue)
    return object : FileDescriptorWriteChannel(this, handler, buffer) {
        override val eventAwaiter get() = awaiter
        override fun closeDescriptor() = Os.close(this@openWriteChannel)
    }
}
