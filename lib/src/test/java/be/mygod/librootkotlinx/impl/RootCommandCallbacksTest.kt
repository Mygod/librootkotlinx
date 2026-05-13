package be.mygod.librootkotlinx.impl

import android.os.Parcelable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCommandCallbacksTest {
    @Test
    fun ordinarySuccessCompletesAndUnregistersCallback() = runTest {
        val callbacks = RootCommandCallbacks()
        val result = CompletableDeferred<Parcelable?>()
        val registered = callbacks.register { RootCommandCallback.Ordinary(null, result) }

        val handling = callbacks.handleResponse(
            registered.id,
            RootCommandResponse(RootCommandResponse.SUCCESS, null),
        )

        assertEquals(RootCommandResponseHandling.Done, handling)
        assertNull(result.await())
        assertFalse(callbacks.unregister(registered.id, registered.callback))
    }

    @Test
    fun flowResponseToClosedChannelCancelsRemoteAndUnregistersCallback() {
        val callbacks = RootCommandCallbacks()
        val channel = Channel<Parcelable?>(capacity = 1)
        val registered = callbacks.register { RootCommandCallback.Flow(null, channel) }
        channel.close()

        val handling = callbacks.handleResponse(
            registered.id,
            RootCommandResponse(RootCommandResponse.SUCCESS, null),
        )

        assertEquals(RootCommandResponseHandling.CancelRemote, handling)
        assertFalse(callbacks.unregister(registered.id, registered.callback))
    }

    @Test
    fun callbackFailureUnregistersCallback() {
        val callbacks = RootCommandCallbacks()
        val channel = Channel<Parcelable?>(capacity = 1)
        val registered = callbacks.register { RootCommandCallback.Flow(null, channel) }

        val handling = callbacks.handleResponse(
            registered.id,
            RootCommandResponse(RootCommandResponse.EX_PARCELABLE, null),
        )

        assertEquals(RootCommandResponseHandling.Done, handling)
        assertFalse(callbacks.unregister(registered.id, registered.callback))
    }

    @Test
    fun closeAllClosesPendingCallbacks() = runTest {
        val callbacks = RootCommandCallbacks()
        val result = CompletableDeferred<Parcelable?>()
        val registered = callbacks.register { RootCommandCallback.Ordinary(null, result) }
        val cause = RuntimeException("closed")

        callbacks.closeAll(cause)

        assertTrue(result.isCompleted)
        try {
            result.await()
            throw AssertionError("Expected callback failure")
        } catch (e: RuntimeException) {
            assertEquals(cause.message, e.message)
        }
        assertFalse(callbacks.unregister(registered.id, registered.callback))
    }
}
