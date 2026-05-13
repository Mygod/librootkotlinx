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
    private var closed = false
    private var dispatching = false
    private var inputContinuation: CancellableContinuation<Unit>? = null
    private var outputContinuation: CancellableContinuation<Unit>? = null

    suspend fun await(input: Boolean) = suspendCancellableCoroutine { continuation ->
        var cancelCause: CancellationException? = null
        synchronized(this) {
            if (closed) cancelCause = CancellationException("File descriptor listener closed") else {
                if (input) {
                    check(inputContinuation == null) { "Already waiting for file descriptor input readiness" }
                    inputContinuation = continuation
                } else {
                    check(outputContinuation == null) { "Already waiting for file descriptor output readiness" }
                    outputContinuation = continuation
                }
                continuation.invokeOnCancellation {
                    synchronized(this) {
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

    override fun onFileDescriptorEvents(fd: FileDescriptor, events: Int): Int {
        var inputContinuation: CancellableContinuation<Unit>? = null
        var outputContinuation: CancellableContinuation<Unit>? = null
        synchronized(this) {
            if (closed) return 0
            dispatching = true
            inputContinuation = if (events and (
                    MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT or
                    MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR) != 0
            ) this.inputContinuation.also { this.inputContinuation = null } else null
            outputContinuation = if (events and (
                    MessageQueue.OnFileDescriptorEventListener.EVENT_OUTPUT or
                    MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR) != 0
            ) this.outputContinuation.also { this.outputContinuation = null } else null
        }
        var nextEvents: Int
        try {
            if (inputContinuation?.isActive == true) inputContinuation.resume(Unit)
            if (outputContinuation?.isActive == true) outputContinuation.resume(Unit)
        } finally {
            nextEvents = synchronized(this) {
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
        synchronized(this) {
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
}
