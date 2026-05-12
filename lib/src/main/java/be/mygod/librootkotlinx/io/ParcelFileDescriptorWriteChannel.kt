@file:JvmName("ParcelFileDescriptorChannels")
@file:JvmMultifileClass

package be.mygod.librootkotlinx.io

import android.os.Handler
import android.os.Looper
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Write channel backed by an owned [ParcelFileDescriptor] registered on a [MessageQueue].
 */
internal class ParcelFileDescriptorWriteChannel(
    private val descriptor: ParcelFileDescriptor,
    looper: Looper = Looper.getMainLooper(),
    private val buffer: ByteArray = ByteArray(DEFAULT_BUFFER_SIZE),
    private val channel: ByteChannel = ByteChannel(),
) : ByteWriteChannel by channel {
    private val fileDescriptor = descriptor.fileDescriptor
    private val handler = Handler(looper)
    private val eventAwaiter = FileDescriptorEventAwaiter(fileDescriptor, handler)
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
                                    eventAwaiter.await(MessageQueue.OnFileDescriptorEventListener.EVENT_OUTPUT)
                                    continue
                                }
                                OsConstants.EINTR -> continue
                                else -> throw e
                            }
                        }
                        if (written > 0) {
                            offset += written
                        } else {
                            eventAwaiter.await(MessageQueue.OnFileDescriptorEventListener.EVENT_OUTPUT)
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
                closeDescriptor()?.let { closeError ->
                    failure?.addSuppressed(closeError) ?: throw closeError
                }
            }
        }
    }

    override fun cancel(cause: Throwable?) {
        val closeError = closeDescriptor()
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

    private fun closeDescriptor(): IOException? {
        if (!closed.compareAndSet(false, true)) return null
        eventAwaiter.close()
        return try {
            descriptor.close()
            null
        } catch (e: IOException) {
            e
        }
    }
}

/**
 * Opens a write channel that owns this entire [ParcelFileDescriptor].
 *
 * Closing or cancelling the returned channel closes the descriptor. This API does not provide socket-style half shutdown.
 */
fun ParcelFileDescriptor.openWriteChannel(
    looper: Looper = Looper.getMainLooper(),
    buffer: ByteArray = ByteArray(DEFAULT_BUFFER_SIZE),
): ByteWriteChannel = ParcelFileDescriptorWriteChannel(this, looper, buffer)
