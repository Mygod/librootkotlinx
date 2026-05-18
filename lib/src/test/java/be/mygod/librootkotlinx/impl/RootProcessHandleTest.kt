package be.mygod.librootkotlinx.impl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RootProcessHandleTest {
    @Test
    fun rootIoExitBeforeConnectionFailsStartupWhileActive() = runTest {
        supervisorScope {
            val rootIoFailure = IOException("stdio closed")
            val handlerCompletion = CompletableDeferred<Throwable?>()
            val startup = async { RootProcessHandle.awaitRootServiceConnected(Job(), handlerCompletion) }

            runCurrent()
            handlerCompletion.complete(rootIoFailure)

            val thrown = awaitRootIoExit(startup)
            assertSame(rootIoFailure, thrown.rootIoCause())
        }
    }

    @Test
    fun activeRootIoCancellationBeforeConnectionStillFailsStartup() = runTest {
        supervisorScope {
            val rootIoCancellation = CancellationException("handler stopped")
            val handlerCompletion = CompletableDeferred<Throwable?>()
            val startup = async { RootProcessHandle.awaitRootServiceConnected(Job(), handlerCompletion) }

            runCurrent()
            handlerCompletion.complete(rootIoCancellation)

            val thrown = awaitRootIoExit(startup)
            assertSame(rootIoCancellation, thrown.rootIoCause())
        }
    }

    @Test
    fun cancelledRootIoHandlerBeforeConnectionPreservesCancellation() = runTest {
        supervisorScope {
            val rootIoCancellation = CancellationException("startup owner closed")
            val handlerCompletion = CompletableDeferred<Throwable?>()
            val startup = async { RootProcessHandle.awaitRootServiceConnected(Job(), handlerCompletion) }

            runCurrent()
            handlerCompletion.cancel(rootIoCancellation)

            assertSame(rootIoCancellation, awaitCancellation(startup).unwrap())
        }
    }

    private suspend fun awaitRootIoExit(startup: Deferred<Unit>) = try {
        startup.await()
        throw AssertionError("Expected root IO startup failure")
    } catch (e: IOException) {
        assertEquals(ROOT_IO_EXIT_MESSAGE, e.message)
        e
    }

    private suspend fun awaitCancellation(startup: Deferred<Unit>) = try {
        startup.await()
        fail("Expected startup cancellation")
        throw AssertionError()
    } catch (e: CancellationException) {
        e
    }

    private fun CancellationException.unwrap(): CancellationException = cause as? CancellationException ?: this

    private fun IOException.rootIoCause(): Throwable? =
        (cause as? IOException)?.takeIf { it.message == ROOT_IO_EXIT_MESSAGE }?.cause ?: cause

    private companion object {
        const val ROOT_IO_EXIT_MESSAGE = "Root IO handling completed before root service connected"
    }
}
