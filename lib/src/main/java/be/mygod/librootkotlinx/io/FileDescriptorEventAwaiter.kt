package be.mygod.librootkotlinx.io

import android.os.MessageQueue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.FileDescriptor
import kotlin.coroutines.resume

/**
 * Readiness awaiter backed by one [MessageQueue] fd record.
 */
internal class FileDescriptorEventAwaiter(
    private val fileDescriptor: FileDescriptor,
    private val messageQueue: MessageQueue,
) : MessageQueue.OnFileDescriptorEventListener {
    private val lock = Any()
    private var closed = false
    private var dispatching = false
    private var inputContinuation: CancellableContinuation<Unit>? = null
    private var outputContinuation: CancellableContinuation<Unit>? = null

    suspend fun await(events: Int) {
        check(events == MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT ||
                events == MessageQueue.OnFileDescriptorEventListener.EVENT_OUTPUT) {
            "Only input or output readiness can be awaited"
        }
        suspendCancellableCoroutine { continuation ->
            var cancelCause: CancellationException? = null
            synchronized(lock) {
                if (closed) {
                    cancelCause = CancellationException("File descriptor listener closed")
                } else {
                    when (events) {
                        MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT -> {
                            check(inputContinuation == null) { "Already waiting for file descriptor input readiness" }
                            inputContinuation = continuation
                        }
                        MessageQueue.OnFileDescriptorEventListener.EVENT_OUTPUT -> {
                            check(outputContinuation == null) { "Already waiting for file descriptor output readiness" }
                            outputContinuation = continuation
                        }
                    }
                    continuation.invokeOnCancellation {
                        synchronized(lock) {
                            if (inputContinuation === continuation) inputContinuation = null
                            if (outputContinuation === continuation) outputContinuation = null
                            updateListenerLocked()
                        }
                    }
                    updateListenerLocked()
                }
            }
            cancelCause?.let { continuation.cancel(it) }
        }
    }

    override fun onFileDescriptorEvents(fd: FileDescriptor, events: Int): Int {
        var inputContinuation: CancellableContinuation<Unit>? = null
        var outputContinuation: CancellableContinuation<Unit>? = null
        synchronized(lock) {
            if (closed) return 0
            dispatching = true
            inputContinuation = if (events and (
                    MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT or
                    MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR) != 0
            ) {
                this.inputContinuation.also { this.inputContinuation = null }
            } else null
            outputContinuation = if (events and (
                    MessageQueue.OnFileDescriptorEventListener.EVENT_OUTPUT or
                    MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR) != 0
            ) {
                this.outputContinuation.also { this.outputContinuation = null }
            } else null
        }
        var nextEvents = 0
        try {
            resume(inputContinuation)
            resume(outputContinuation)
        } finally {
            nextEvents = synchronized(lock) {
                dispatching = false
                listenerEventsLocked()
            }
        }
        return nextEvents
    }

    fun close() {
        var inputContinuation: CancellableContinuation<Unit>? = null
        var outputContinuation: CancellableContinuation<Unit>? = null
        var removeListener = false
        synchronized(lock) {
            if (closed) return
            closed = true
            inputContinuation = this.inputContinuation.also { this.inputContinuation = null }
            outputContinuation = this.outputContinuation.also { this.outputContinuation = null }
            removeListener = !dispatching
        }
        if (removeListener) messageQueue.removeOnFileDescriptorEventListener(fileDescriptor)
        val cause = CancellationException("File descriptor listener closed")
        inputContinuation?.cancel(cause)
        outputContinuation?.cancel(cause)
    }

    private fun updateListenerLocked() {
        if (dispatching) return
        val events = listenerEventsLocked()
        if (events == 0) {
            messageQueue.removeOnFileDescriptorEventListener(fileDescriptor)
        } else {
            messageQueue.addOnFileDescriptorEventListener(fileDescriptor, events, this)
        }
    }

    private fun listenerEventsLocked(): Int {
        var events = 0
        if (inputContinuation != null) events = events or MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT
        if (outputContinuation != null) events = events or MessageQueue.OnFileDescriptorEventListener.EVENT_OUTPUT
        if (events != 0) events = events or MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR
        return events
    }

    private fun resume(continuation: CancellableContinuation<Unit>?) {
        if (continuation == null) return
        if (continuation.isActive) continuation.resume(Unit)
    }
}
