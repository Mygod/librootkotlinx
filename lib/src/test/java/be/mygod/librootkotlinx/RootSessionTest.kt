package be.mygod.librootkotlinx

import be.mygod.librootkotlinx.impl.IRootCommandService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RootSessionTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cancelledStartupAcquireClosesPartiallyInitializedServer() = runTest {
        val initStarted = CompletableDeferred<Unit>()
        val finishStartup = CompletableDeferred<Unit>()
        lateinit var startedServer: RootServer
        val session = object : TestRootSession() {
            override suspend fun initServer(server: RootServer) {
                startedServer = server
                initStarted.complete(Unit)
                withContext(NonCancellable) {
                    finishStartup.await()
                    server.markActive()
                }
            }
        }
        val acquire = async { session.acquire() }

        initStarted.await()
        acquire.cancel()
        finishStartup.complete(Unit)
        acquire.join()

        assertFalse(startedServer.active)
    }

    @Test
    fun cancelledStartupAcquireLetsWaitingAcquireRetry() = runTest {
        val initStarted = CompletableDeferred<Unit>()
        var attempts = 0
        val session = object : TestRootSession() {
            override suspend fun initServer(server: RootServer) {
                if (++attempts == 1) {
                    initStarted.complete(Unit)
                    awaitCancellation()
                } else {
                    server.markActive()
                }
            }
        }
        val first = async { session.acquire() }

        initStarted.await()
        val second = async { session.acquire() }
        first.cancelAndJoin()
        val server = second.await()

        assertEquals(2, attempts)
        assertTrue(server.active)
        session.closeExisting()
    }

    @Test
    fun closeExistingDuringStartupClosesOnRelease() = runTest {
        val initStarted = CompletableDeferred<Unit>()
        val finishStartup = CompletableDeferred<Unit>()
        val session = object : TestRootSession() {
            override suspend fun initServer(server: RootServer) {
                initStarted.complete(Unit)
                finishStartup.await()
                server.markActive()
            }
        }
        val acquire = async { session.acquire() }

        initStarted.await()
        val close = async { session.closeExisting() }
        runCurrent()
        assertTrue(close.isCompleted)
        assertFalse(acquire.isCompleted)
        finishStartup.complete(Unit)
        val server = acquire.await()
        close.await()

        assertTrue(server.active)
        session.release(server)
        assertFalse(server.active)
    }

    @Test
    fun closeExistingDuringStartupSurvivesWaitingAcquire() = runTest {
        val initStarted = CompletableDeferred<Unit>()
        val finishStartup = CompletableDeferred<Unit>()
        val session = object : TestRootSession() {
            override suspend fun initServer(server: RootServer) {
                initStarted.complete(Unit)
                finishStartup.await()
                server.markActive()
            }
        }
        val first = async { session.acquire() }

        initStarted.await()
        val second = async { session.acquire() }
        val close = async { session.closeExisting() }
        runCurrent()
        assertTrue(close.isCompleted)
        assertFalse(second.isCompleted)
        close.await()
        finishStartup.complete(Unit)
        val server = first.await()

        assertTrue(server.active)
        assertTrue(second.await() === server)
        session.release(server)
        assertTrue(server.active)
        session.release(server)
        assertFalse(server.active)
    }

    @Test
    fun failedStartupAcquireAllowsRetry() = runTest {
        var attempts = 0
        val session = object : TestRootSession() {
            override suspend fun initServer(server: RootServer) {
                if (++attempts == 1) throw TestStartupException()
                server.markActive()
            }
        }

        try {
            session.acquire()
            fail("Expected startup failure")
        } catch (_: TestStartupException) { }
        val server = session.acquire()

        assertEquals(2, attempts)
        assertTrue(server.active)
        session.closeExisting()
    }

    @Test
    fun failedStartupAcquirePropagatesFailureToWaitingAcquire() = runTest {
        val initStarted = CompletableDeferred<Unit>()
        val failStartup = CompletableDeferred<Unit>()
        val failure = TestStartupException()
        var attempts = 0
        val session = object : TestRootSession() {
            override suspend fun initServer(server: RootServer) {
                ++attempts
                initStarted.complete(Unit)
                failStartup.await()
                throw failure
            }
        }
        supervisorScope {
            val first = async { session.acquire() }

            initStarted.await()
            val second = async { session.acquire() }
            runCurrent()
            assertFalse(second.isCompleted)
            failStartup.complete(Unit)

            assertSame(failure, awaitStartupFailure(first))
            assertSame(failure, awaitStartupFailure(second))
            assertEquals(1, attempts)
        }
    }

    @Test
    fun releaseClosesIdleServerAfterTimeout() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = object : TestRootSession() {
            override val timeout get() = 1.seconds
            override val timeoutDispatcher get() = dispatcher
            override suspend fun initServer(server: RootServer) = server.markActive()
        }
        val server = session.acquire()

        session.release(server)
        advanceTimeBy(999)
        runCurrent()
        assertTrue(server.active)
        advanceTimeBy(1)
        runCurrent()

        assertFalse(server.active)
    }

    private class TestStartupException : RuntimeException()

    private abstract class TestRootSession : RootSession() {
        override val context get() = throw AssertionError("Unexpected context access")
    }

    private suspend fun awaitStartupFailure(acquire: Deferred<RootServer>): TestStartupException = try {
        acquire.await()
        throw AssertionError("Expected startup failure")
    } catch (e: TestStartupException) {
        e
    }

    private fun RootServer.markActive() {
        RootServer::class.java.getDeclaredField("service").apply {
            isAccessible = true
            set(this@markActive, Proxy.newProxyInstance(
                IRootCommandService::class.java.classLoader,
                arrayOf(IRootCommandService::class.java),
            ) { _, _, _ -> null })
        }
    }
}
