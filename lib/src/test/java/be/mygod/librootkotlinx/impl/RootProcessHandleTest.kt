package be.mygod.librootkotlinx.impl

import android.net.Credentials
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
    fun processExitBeforeOwnershipFailsStartup() = runTest {
        supervisorScope {
            val ownershipAccepted = CompletableDeferred<Credentials>()
            val processExited = CompletableDeferred<Int>()
            val startup = async {
                RootProcessHandle.awaitRootStartup(Job(), ownershipAccepted, processExited) { ": diagnostics" }
            }

            runCurrent()
            processExited.complete(9)

            assertEquals(
                "Root process exited with code 9 before ownership accepted: diagnostics",
                awaitIOException(startup).message,
            )
        }
    }

    @Test
    fun processExitBeforeConnectionFailsStartup() = runTest {
        supervisorScope {
            val ownershipAccepted = CompletableDeferred<Credentials>()
            val processExited = CompletableDeferred<Int>()
            val startup = async {
                RootProcessHandle.awaitRootStartup(Job(), ownershipAccepted, processExited) { ": diagnostics" }
            }

            ownershipAccepted.complete(Credentials(123, 0, 0))
            runCurrent()
            processExited.complete(7)

            assertEquals(
                "Root process exited with code 7 before root service connected: diagnostics",
                awaitIOException(startup).message,
            )
        }
    }

    @Test
    fun connectionCompletingAfterOwnershipCompletesStartup() = runTest {
        supervisorScope {
            val rootServiceConnected = Job()
            val ownershipAccepted = CompletableDeferred<Credentials>()
            val processExited = CompletableDeferred<Int>()
            val credentials = Credentials(123, 0, 0)
            var diagnosticsCalls = 0
            val startup = async {
                RootProcessHandle.awaitRootStartup(rootServiceConnected, ownershipAccepted, processExited) {
                    ++diagnosticsCalls
                    ""
                }
            }

            ownershipAccepted.complete(credentials)
            runCurrent()
            rootServiceConnected.complete()

            assertEquals(credentials, startup.await())
            assertEquals(0, diagnosticsCalls)
        }
    }

    @Test
    fun cancelledConnectionCancelsStartupAfterOwnership() = runTest {
        supervisorScope {
            val rootServiceConnected = Job().also { it.cancel(CancellationException("server closed")) }
            val ownershipAccepted = CompletableDeferred<Credentials>()
            val processExited = CompletableDeferred<Int>()
            var diagnosticsCalls = 0
            val startup = async {
                RootProcessHandle.awaitRootStartup(rootServiceConnected, ownershipAccepted, processExited) {
                    ++diagnosticsCalls
                    ""
                }
            }

            ownershipAccepted.complete(Credentials(123, 0, 0))

            assertEquals("server closed", awaitCancellation(startup).message)
            assertEquals(0, diagnosticsCalls)
        }
    }

    private suspend fun awaitIOException(startup: Deferred<*>) = try {
        startup.await()
        fail("Expected startup failure")
        throw AssertionError()
    } catch (e: IOException) {
        e
    }

    private suspend fun awaitCancellation(startup: Deferred<*>): CancellationException = try {
        startup.await()
        fail("Expected startup cancellation")
        throw AssertionError()
    } catch (e: CancellationException) {
        e
    }
}
