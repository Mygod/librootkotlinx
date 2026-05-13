@file:JvmName("FileDescriptorChannels")
@file:JvmMultifileClass

package be.mygod.librootkotlinx.io

import android.annotation.SuppressLint
import android.os.Handler
import android.os.MessageQueue
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read channel backed by a [FileDescriptor] registered on a [MessageQueue].
 */
internal abstract class FileDescriptorReadChannel(
    private val fileDescriptor: FileDescriptor,
    handler: Handler,
    private val buffer: ByteArray,
    private val channel: ByteChannel = ByteChannel(autoFlush = true),
) : ByteReadChannel by channel {
    private val defaultEventAwaiter = lazy { FileDescriptorEventAwaiter(fileDescriptor, handler.looper.queue) }
    private val closed = AtomicBoolean()
    private val drainLock = Mutex()
    @Volatile
    private var drainFailure: Throwable? = null
    private val drainJob: Job

    init {
        fileDescriptor.isNonblocking = true
        drainJob = CoroutineScope(handler.asCoroutineDispatcher("pfd-reader")).launch {
            try {
                while (drainAvailable()) {
                    awaitEvent(input = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                complete(e)
            }
        }
    }

    /**
     * Drain currently readable descriptor bytes into this channel without waiting for more fd input.
     */
    suspend fun drain() {
        drainFailure?.let { throw it }
        try {
            if (!drainAvailable()) drainJob.cancel()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            complete(e)
            throw e
        }
        drainFailure?.let { throw it }
    }

    override fun cancel(cause: Throwable?) {
        complete(cause)
        drainJob.cancel()
    }

    /**
     * @return true if reading stopped because the descriptor would block, false after EOF/close.
     */
    private suspend fun drainAvailable(): Boolean = drainLock.withLock {
        while (!channel.isClosedForWrite) {
            val count = try {
                Os.read(fileDescriptor, buffer, 0, buffer.size)
            } catch (e: ErrnoException) {
                when (e.errno) {
                    OsConstants.EAGAIN -> return true
                    OsConstants.EINTR -> continue
                    OsConstants.EBADF -> {
                        complete()
                        return false
                    }
                    else -> throw e
                }
            }
            if (count == 0) {
                complete()
                return false
            }
            channel.writeFully(buffer, 0, count)
        }
        false
    }

    private fun complete(cause: Throwable? = null) {
        val closeError = if (!closed.compareAndSet(false, true)) null else {
            closeEvents()
            try {
                closeDescriptor()
                null
            } catch (e: IOException) {
                e
            }
        }
        if (cause == null) {
            if (closeError == null) {
                if (!channel.isClosedForWrite) channel.close()
            } else {
                drainFailure = closeError
                channel.cancel(closeError)
            }
        } else {
            if (closeError != null) cause.addSuppressed(closeError)
            drainFailure = cause
            channel.cancel(cause)
        }
    }

    protected open val eventAwaiter: FileDescriptorEventAwaiter
        get() = defaultEventAwaiter.value

    protected open suspend fun awaitEvent(input: Boolean) = eventAwaiter.await(input)

    protected open fun closeEvents() {
        if (defaultEventAwaiter.isInitialized()) defaultEventAwaiter.value.close()
    }

    protected abstract fun closeDescriptor()
}

/**
 * Opens a read channel that owns this entire [ParcelFileDescriptor].
 *
 * The descriptor is registered on [handler]'s [MessageQueue].
 *
 * Closing or cancelling the returned channel closes the descriptor. This API does not provide socket-style half shutdown.
 */
fun ParcelFileDescriptor.openReadChannel(
    handler: Handler,
    buffer: ByteArray = ByteArray(DEFAULT_BUFFER_SIZE),
): ByteReadChannel = object : FileDescriptorReadChannel(fileDescriptor, handler, buffer) {
    override fun closeDescriptor() = close()
}

/**
 * Opens a read channel that owns this [FileDescriptor].
 *
 * The descriptor is registered on [handler]'s [MessageQueue].
 *
 * Closing or cancelling the returned channel closes the descriptor. This API does not provide socket-style half shutdown.
 */
fun FileDescriptor.openReadChannel(
    handler: Handler,
    buffer: ByteArray = ByteArray(DEFAULT_BUFFER_SIZE),
): ByteReadChannel = object : FileDescriptorReadChannel(this, handler, buffer) {
    override fun closeDescriptor() {
        try {
            Os.close(this@openReadChannel)
        } catch (e: ErrnoException) {
            throw IOException(e)
        }
    }
}

var FileDescriptor.isNonblocking: Boolean
    @SuppressLint("NewApi")
    get() = Os.fcntlInt(this, OsConstants.F_GETFL, 0) and OsConstants.O_NONBLOCK != 0
    @SuppressLint("NewApi")
    set(value) {
        val flags = Os.fcntlInt(this, OsConstants.F_GETFL, 0)
        Os.fcntlInt(this, OsConstants.F_SETFL, if (value) {
            flags or OsConstants.O_NONBLOCK
        } else {
            flags and OsConstants.O_NONBLOCK.inv()
        })
    }

suspend fun ByteReadChannel.forEachLine(block: (String) -> Unit) {
    try {
        while (true) block(readLine() ?: break)
    } finally {
        cancel(null)
    }
}
