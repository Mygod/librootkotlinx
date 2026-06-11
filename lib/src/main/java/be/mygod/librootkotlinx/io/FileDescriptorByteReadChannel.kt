@file:JvmName("FileDescriptorChannels")
@file:JvmMultifileClass

package be.mygod.librootkotlinx.io

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.MessageQueue
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Byte read channel backed by a [FileDescriptor].
 */
interface FileDescriptorByteReadChannel : ByteReadChannel {
    /**
     * Drain currently readable descriptor bytes into this channel without waiting for more fd input.
     */
    suspend fun drain()
}

/**
 * Read channel implementation backed by a [FileDescriptor] registered on a [MessageQueue].
 */
internal abstract class FileDescriptorByteReadChannelImpl(
    private val fileDescriptor: FileDescriptor,
    handler: Handler,
    private val buffer: ByteArray,
    private val unbounded: Boolean = false,
    private val channel: ByteChannel = ByteChannel(autoFlush = true),
) : FileDescriptorByteReadChannel, ByteReadChannel by channel {
    private val closed = AtomicBoolean()
    private val drainLock = Mutex()
    @Volatile
    private var drainFailure: Throwable? = null
    private val drainJob: Job
    protected abstract val eventAwaiter: FileDescriptorEventAwaiter

    init {
        fileDescriptor.isNonblocking = true
        drainJob = CoroutineScope(handler.asCoroutineDispatcher("pfd-reader")).launch {
            try {
                while (drainAvailable()) {
                    eventAwaiter.await(input = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                complete(e)
            }
        }
    }

    override suspend fun drain() {
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
    @OptIn(InternalAPI::class)
    private suspend fun drainAvailable(): Boolean = drainLock.withLock {
        while (!channel.isClosedForWrite) {
            val count = try {
                // BlockGuardOs reports all Os.read calls as disk reads; this fd is nonblocking.
                val policy = StrictMode.allowThreadDiskReads()
                try {
                    Os.read(fileDescriptor, buffer, 0, buffer.size)
                } finally {
                    StrictMode.setThreadPolicy(policy)
                }
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
            if (unbounded) {
                channel.writeBuffer.write(buffer, 0, count)
                channel.flushWriteBuffer()
            } else channel.writeFully(buffer, 0, count)
        }
        false
    }

    private fun complete(cause: Throwable? = null) {
        if (closed.compareAndSet(false, true)) {
            closeEvents()
            closeDescriptor()
        }
        if (cause != null) {
            drainFailure = cause
            channel.cancel(cause)
        } else if (!channel.isClosedForWrite) channel.close()
    }

    protected abstract fun closeDescriptor()
    protected open fun closeEvents() = eventAwaiter.close()
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
): FileDescriptorByteReadChannel {
    val awaiter = FileDescriptorEventAwaiter(fileDescriptor, handler.looper.queue)
    return object : FileDescriptorByteReadChannelImpl(fileDescriptor, handler, buffer) {
        override val eventAwaiter get() = awaiter
        override fun closeDescriptor() = close()
    }
}

internal fun ParcelFileDescriptor.openUnboundedReadChannel(
    handler: Handler,
    buffer: ByteArray = ByteArray(DEFAULT_BUFFER_SIZE),
): FileDescriptorByteReadChannel {
    val awaiter = FileDescriptorEventAwaiter(fileDescriptor, handler.looper.queue)
    return object : FileDescriptorByteReadChannelImpl(fileDescriptor, handler, buffer, unbounded = true) {
        override val eventAwaiter get() = awaiter
        override fun closeDescriptor() = close()
    }
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
): FileDescriptorByteReadChannel {
    val awaiter = FileDescriptorEventAwaiter(this, handler.looper.queue)
    return object : FileDescriptorByteReadChannelImpl(this, handler, buffer) {
        override val eventAwaiter get() = awaiter
        override fun closeDescriptor() = Os.close(this@openReadChannel)
    }
}

private val setBlocking by lazy {
    Class.forName("libcore.io.IoUtils").getMethod("setBlocking", FileDescriptor::class.java,
        Boolean::class.javaPrimitiveType)
}
/**
 * Caller beware: the getter is mainly for diagnostic purposes.
 * Calling it requires private API bypassing only on API 28-29.
 */
var FileDescriptor.isNonblocking: Boolean
    @SuppressLint("NewApi")
    get() = Os.fcntlInt(this, OsConstants.F_GETFL, 0) and OsConstants.O_NONBLOCK != 0
    @SuppressLint("NewApi")
    set(value) {
        var setBlockingFailure: ReflectiveOperationException? = null
        if (Build.VERSION.SDK_INT < 30) {
            try {
                setBlocking(null, this, !value)
                return
            } catch (e: ReflectiveOperationException) {
                setBlockingFailure = e
            }
        }
        try {
            val flags = Os.fcntlInt(this, OsConstants.F_GETFL, 0)
            Os.fcntlInt(this, OsConstants.F_SETFL, if (value) {
                flags or OsConstants.O_NONBLOCK
            } else flags and OsConstants.O_NONBLOCK.inv())
        } catch (e: Throwable) {
            if (setBlockingFailure != null) e.addSuppressed(setBlockingFailure)
            throw e
        }
    }

suspend fun ByteReadChannel.useLines(block: (String) -> Unit) {
    try {
        while (true) block(readLine() ?: break)
    } finally {
        cancel(null)
    }
}
