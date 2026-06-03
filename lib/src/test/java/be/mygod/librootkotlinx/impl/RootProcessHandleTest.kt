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
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RootProcessHandleTest {
    @Test
    fun startupStdioClosedBeforeOwnershipFailsStartup() = runTest {
        supervisorScope {
            val ownershipAccepted = CompletableDeferred<Unit>()
            val startupStdioClosed = CompletableDeferred<Unit>()
            val startup = async {
                RootProcessHandle.awaitRootStartup(Job(), ownershipAccepted, startupStdioClosed) { 9 }
            }

            runCurrent()
            startupStdioClosed.complete(Unit)

            assertEquals(
                "Root process stdout/stderr closed before ownership accepted with exit code 9",
                awaitIOException(startup).message,
            )
        }
    }

    @Test
    fun startupStdioClosedBeforeConnectionFailsStartup() = runTest {
        supervisorScope {
            val ownershipAccepted = CompletableDeferred<Unit>()
            val startupStdioClosed = CompletableDeferred<Unit>()
            val startup = async {
                RootProcessHandle.awaitRootStartup(Job(), ownershipAccepted, startupStdioClosed) { 7 }
            }

            ownershipAccepted.complete(Unit)
            runCurrent()
            startupStdioClosed.complete(Unit)

            assertEquals(
                "Root process stdout/stderr closed before root service connected with exit code 7",
                awaitIOException(startup).message,
            )
        }
    }

    @Test
    fun connectionCompletingAfterOwnershipCompletesStartup() = runTest {
        supervisorScope {
            val rootServiceConnected = Job()
            val ownershipAccepted = CompletableDeferred<Unit>()
            val startupStdioClosed = CompletableDeferred<Unit>()
            var awaitFailureExitCalls = 0
            val startup = async {
                RootProcessHandle.awaitRootStartup(rootServiceConnected, ownershipAccepted, startupStdioClosed) {
                    ++awaitFailureExitCalls
                }
            }

            ownershipAccepted.complete(Unit)
            runCurrent()
            rootServiceConnected.complete()

            startup.await()
            assertEquals(0, awaitFailureExitCalls)
        }
    }

    @Test
    fun cancelledConnectionCancelsStartupAfterOwnership() = runTest {
        supervisorScope {
            val rootServiceConnected = Job().also { it.cancel(CancellationException("server closed")) }
            val ownershipAccepted = CompletableDeferred<Unit>()
            val startupStdioClosed = CompletableDeferred<Unit>()
            var awaitFailureExitCalls = 0
            val startup = async {
                RootProcessHandle.awaitRootStartup(rootServiceConnected, ownershipAccepted, startupStdioClosed) {
                    ++awaitFailureExitCalls
                }
            }

            ownershipAccepted.complete(Unit)

            assertEquals("server closed", awaitCancellation(startup).message)
            assertEquals(0, awaitFailureExitCalls)
        }
    }

    private suspend fun awaitIOException(startup: Deferred<Unit>) = try {
        startup.await()
        fail("Expected startup failure")
        throw AssertionError()
    } catch (e: IOException) {
        e
    }

    private suspend fun awaitCancellation(startup: Deferred<Unit>): CancellationException = try {
        startup.await()
        fail("Expected startup cancellation")
        throw AssertionError()
    } catch (e: CancellationException) {
        e
    }
}
