package be.mygod.librootkotlinx

import android.os.IBinder
import be.mygod.librootkotlinx.impl.IRootCommandService
import be.mygod.librootkotlinx.impl.RootServiceConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import sun.misc.Unsafe
import kotlin.time.Duration.Companion.milliseconds
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
    fun ownedShellFailurePropagatesFromAcquire() = runTest {
        val failure = NoShellException("Root shell is not available")
        val session = object : TestRootSession() {
            override suspend fun initServer(server: RootServer) {
                throw failure
            }
        }

        try {
            session.acquire()
            fail("Expected owned shell failure")
        } catch (e: NoShellException) {
            assertSame(failure, e)
        }
    }

    @Test
    fun appProcessVmOptionOmitsUnsetCoroutineProperties() {
        withSystemProperties(
            DEBUG_PROPERTY_NAME to null,
            "kotlinx.coroutines.stacktrace.recovery" to null,
            "kotlinx.coroutines.debug.enable.creation.stack.trace" to null,
        ) {
            assertNull(object : TestRootSession() { }.appProcessVmOptionForTest())
        }
    }

    @Test
    fun appProcessVmOptionCopiesExplicitCoroutinePropertiesWithQuotedValues() {
        withSystemProperties(
            DEBUG_PROPERTY_NAME to "on",
            "kotlinx.coroutines.stacktrace.recovery" to "false",
            "kotlinx.coroutines.debug.enable.creation.stack.trace" to "client's value",
        ) {
            assertEquals(
                "-Dkotlinx.coroutines.debug='on' -Dkotlinx.coroutines.stacktrace.recovery='false' " +
                        "-Dkotlinx.coroutines.debug.enable.creation.stack.trace='client'\\''s value'",
                object : TestRootSession() { }.appProcessVmOptionForTest(),
            )
        }
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
        advanceTimeBy(999.milliseconds)
        runCurrent()
        assertTrue(server.active)
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertFalse(server.active)
    }

    private class TestStartupException : RuntimeException()

    private abstract class TestRootSession : RootSession() {
        override val context get() = throw AssertionError("Unexpected context access")

        fun appProcessVmOptionForTest() = appProcessVmOption
    }

    private suspend fun awaitStartupFailure(acquire: Deferred<RootServer>): TestStartupException = try {
        acquire.await()
        throw AssertionError("Expected startup failure")
    } catch (e: TestStartupException) {
        e
    }

    private fun RootServer.markActive() {
        RootServer::class.java.getDeclaredField("connected").apply {
            isAccessible = true
            set(this@markActive, connectedService())
        }
    }

    private fun connectedService() = RootServiceConnection.Connected(
        rootServiceConnection(),
        proxy(IBinder::class.java),
        proxy(IRootCommandService::class.java),
    )

    private fun rootServiceConnection(): RootServiceConnection {
        val connection = unsafe.allocateInstance(RootServiceConnection::class.java) as RootServiceConnection
        RootServiceConnection::class.java.getDeclaredField("deathRecipient").apply {
            isAccessible = true
            set(connection, proxy(IBinder.DeathRecipient::class.java))
        }
        return connection
    }

    private companion object {
        val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null) as Unsafe
        }

        fun <T : Any> proxy(type: Class<T>): T = checkNotNull(type.cast(Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
        ) { _, method, _ -> defaultValue(method) }))

        fun defaultValue(method: Method): Any? = when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0F
            java.lang.Double.TYPE -> 0.0
            else -> null
        }

        fun withSystemProperties(vararg values: Pair<String, String?>, block: () -> Unit) {
            val previous = values.associate { it.first to System.getProperty(it.first) }
            try {
                for ((name, value) in values) {
                    if (value == null) System.clearProperty(name) else System.setProperty(name, value)
                }
                block()
            } finally {
                for ((name, value) in previous) {
                    if (value == null) System.clearProperty(name) else System.setProperty(name, value)
                }
            }
        }
    }
}
