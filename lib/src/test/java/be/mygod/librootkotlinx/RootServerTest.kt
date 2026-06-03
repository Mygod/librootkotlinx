package be.mygod.librootkotlinx

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.RemoteException
import be.mygod.librootkotlinx.impl.IRootCommandCallback
import be.mygod.librootkotlinx.impl.IRootCommandService
import be.mygod.librootkotlinx.impl.RootCommandCallback
import be.mygod.librootkotlinx.impl.RootCommandCallbacks
import be.mygod.librootkotlinx.impl.RootCommandRequest
import be.mygod.librootkotlinx.impl.RootCommandResponse
import be.mygod.librootkotlinx.impl.RootServiceConnection
import be.mygod.librootkotlinx.impl.RootServiceHandoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import sun.misc.Unsafe
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class RootServerTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun oneWayExecuteTransactsBeforeReturning() {
        val server = RootServer()
        val service = RecordingRootCommandService()
        server.markConnected(service)

        server.execute(NoOpOneWayCommand)

        assertEquals(1, service.oneWayCalls)
    }

    @Test
    fun oneWayExecuteRemoteFailureClosesServer() {
        val server = RootServer()
        val failure = RemoteException("dead")
        server.markConnected(RecordingRootCommandService(failure))

        try {
            server.execute(NoOpOneWayCommand)
            fail("Expected one-way Binder failure")
        } catch (e: RemoteException) {
            assertSame(failure, e)
        }

        assertFalse(server.active)
    }

    @Test
    fun flowResponseBackpressureCancelsRemoteWithoutLifecycleQueue() {
        val server = RootServer()
        val service = RecordingRootCommandService()
        server.markConnected(service)
        val channel = Channel<Parcelable?>(capacity = 1)
        val registered = server.commandCallbacks().register { RootCommandCallback.Flow(null, channel) }
        channel.close()

        server.callback().onResponse(registered.id, RootCommandResponse.success(null))

        assertEquals(1, service.cancelCalls)
        assertFalse(server.commandCallbacks().unregister(registered.id, registered.callback))
    }

    @Test
    fun responseAfterCloseStartsDoesNotCompleteCommandSuccessfully() {
        val server = RootServer()
        val result = CompletableDeferred<Parcelable?>()
        val registered = server.commandCallbacks().register { RootCommandCallback.Ordinary(null, result) }
        val closeCause = CancellationException("closing")
        server.setCloseCause(closeCause)

        server.callback().onResponse(registered.id, RootCommandResponse.success(null))

        assertFalse(result.isCompleted)
        server.commandCallbacks().closeAll(closeCause)
        assertTrue(result.isCancelled)
    }

    @Test
    fun binderDeathMarksServerInactiveBeforeLifecycleHandlesClose() {
        val server = RootServer()
        server.markConnected(RecordingRootCommandService())

        assertTrue(server.active)

        server.deathRecipient().binderDied()

        assertFalse(server.active)
    }

    @Test
    fun closeAfterFailureDoesNotLogClientShutdown() = runTest {
        val logger = RecordingLogger()
        val previousLogger = Logger.me
        Logger.me = logger
        try {
            val server = RootServer()
            server.markConnected(RecordingRootCommandService())

            server.deathRecipient().binderDied()
            server.close()

            assertTrue(logger.warnings.toString(), logger.warnings.any {
                it.first?.startsWith("Root server closing due to failure: ") == true &&
                        it.second is RootServer.UnexpectedExitException
            })
            assertTrue(logger.warnings.any { it.first == "Root server close cause" && it.second is RootServer.UnexpectedExitException })
            assertFalse(logger.debugs.contains("Shutting down from client"))
        } finally {
            Logger.me = previousLogger
        }
    }

    @Test
    fun startupConnectedEventActivatesServerAndCompletesStartupBarriers() = runTest {
        val server = RootServer()
        val service = RecordingRootCommandService()
        val connection = rootServiceConnection()
        val connected = connectedService(connection, service)
        server.markPendingConnection(connection)

        server.handleEvent(rootServerEvent("Connected", connected))

        assertTrue(server.active)
        assertNull(server.pendingConnection())
        assertTrue(server.started().isCompleted)
        assertTrue(server.rootServiceConnected().isCompleted)
    }

    @Test
    fun startupFailureEventFromPendingConnectionClosesHandoffAndRecordsCause() = runTest {
        val server = RootServer()
        val connection = rootServiceConnection()
        val registration = RootServiceHandoff.register { true }
        val failure = RuntimeException("startup failed")
        connection.setHandoff(registration)
        server.markPendingConnection(connection)

        server.handleEvent(rootServerEvent("StartupFailed", connection, failure))

        assertSame(failure, server.closeCause())
        assertFalse(RootServiceHandoff.deliver(registration.token, proxy(IBinder::class.java)))
        server.cleanup()
        assertFalse(server.active)
        assertTrue(server.started().isCompleted)
    }

    @Test
    fun cleanupClosesConnectionDeliveredService() = runTest {
        val server = RootServer()
        val service = RecordingRootCommandService()
        val connection = rootServiceConnection()
        connection.markConnected(connectedService(connection, service))
        server.markPendingConnection(connection)
        server.setCloseCause(CancellationException("closing"))

        server.cleanup()

        assertEquals(1, service.closeCalls)
    }

    private class RecordingRootCommandService(
        private val oneWayFailure: RemoteException? = null,
    ) : IRootCommandService {
        var oneWayCalls = 0
        var cancelCalls = 0
        var closeCalls = 0

        override fun execute(id: Long, request: RootCommandRequest, callback: IRootCommandCallback) = Unit

        override fun executeOneWay(request: RootCommandRequest) {
            ++oneWayCalls
            oneWayFailure?.let { throw it }
        }

        override fun cancel(id: Long) {
            ++cancelCalls
        }

        override fun close() {
            ++closeCalls
        }

        override fun asBinder(): IBinder = proxy(IBinder::class.java)
    }

    private class RecordingLogger : Logger {
        val debugs = mutableListOf<String?>()
        val warnings = mutableListOf<Pair<String?, Throwable?>>()

        override fun d(m: String?, t: Throwable?) {
            debugs += m
        }

        override fun w(m: String?, t: Throwable?) {
            warnings += m to t
        }
    }

    private object NoOpOneWayCommand : RootCommandOneWay {
        override suspend fun execute() = Unit
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private fun RootServer.markConnected(service: IRootCommandService) {
        RootServer::class.java.getDeclaredField("connected").apply {
            isAccessible = true
            set(this@markConnected, connectedService(service))
        }
    }

    private fun RootServer.markPendingConnection(connection: RootServiceConnection) {
        RootServer::class.java.getDeclaredField("pendingConnection").apply {
            isAccessible = true
            set(this@markPendingConnection, connection)
        }
    }

    private fun RootServer.pendingConnection(): RootServiceConnection? =
        RootServer::class.java.getDeclaredField("pendingConnection").let {
            it.isAccessible = true
            it.get(this) as RootServiceConnection?
        }

    @Suppress("UNCHECKED_CAST")
    private fun RootServer.started(): CompletableDeferred<Unit> =
        RootServer::class.java.getDeclaredField("started").let {
            it.isAccessible = true
            it.get(this) as CompletableDeferred<Unit>
        }

    private fun RootServer.rootServiceConnected(): Job =
        RootServer::class.java.getDeclaredField("rootServiceConnected").let {
            it.isAccessible = true
            it.get(this) as Job
        }

    private fun RootServer.closeCause(): Throwable? =
        RootServer::class.java.getDeclaredField("closeCause").let {
            it.isAccessible = true
            it.get(this) as Throwable?
        }

    private fun RootServiceConnection.markConnected(connected: RootServiceConnection.Connected) {
        RootServiceConnection::class.java.getDeclaredField("connected").apply {
            isAccessible = true
            set(this@markConnected, connected)
        }
    }

    private fun RootServiceConnection.setHandoff(registration: RootServiceHandoff.Registration) {
        RootServiceConnection::class.java.getDeclaredField("handoff").apply {
            isAccessible = true
            set(this@setHandoff, registration)
        }
    }

    private fun RootServer.setCloseCause(cause: Throwable) {
        RootServer::class.java.getDeclaredField("closeCause").apply {
            isAccessible = true
            set(this@setCloseCause, cause)
        }
    }

    private fun RootServer.commandCallbacks(): RootCommandCallbacks =
        RootServer::class.java.getDeclaredField("commandCallbacks").let {
            it.isAccessible = true
            it.get(this) as RootCommandCallbacks
        }

    private fun RootServer.callback(): IRootCommandCallback =
        RootServer::class.java.getDeclaredField("callback").let {
            it.isAccessible = true
            it.get(this) as IRootCommandCallback
        }

    private fun RootServer.deathRecipient(): IBinder.DeathRecipient =
        RootServer::class.java.getDeclaredField("callback").let {
            it.isAccessible = true
            it.get(this) as IBinder.DeathRecipient
        }

    private fun connectedService(service: IRootCommandService) =
        connectedService(rootServiceConnection(), service)

    private fun connectedService(connection: RootServiceConnection, service: IRootCommandService) =
        RootServiceConnection.Connected(connection, proxy(IBinder::class.java), service)

    private fun rootServiceConnection(): RootServiceConnection {
        val connection = unsafe.allocateInstance(RootServiceConnection::class.java) as RootServiceConnection
        RootServiceConnection::class.java.getDeclaredField("deathRecipient").apply {
            isAccessible = true
            set(connection, proxy(IBinder.DeathRecipient::class.java))
        }
        return connection
    }

    private suspend fun RootServer.cleanup() = callSuspend("cleanup")

    private fun RootServer.handleEvent(event: Any) {
        val method = javaClass.getDeclaredMethod(
            "handleEvent", Class.forName("${RootServer::class.java.name}\$Event"),
        ).apply { isAccessible = true }
        try {
            method.invoke(this, event)
        } catch (e: InvocationTargetException) {
            throw checkNotNull(e.cause)
        }
    }

    private companion object {
        val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null) as Unsafe
        }

        fun rootServerEvent(name: String, vararg args: Any): Any {
            val type = Class.forName("${RootServer::class.java.name}\$Event\$$name")
            return type.declaredConstructors.single().let {
                it.isAccessible = true
                it.newInstance(*args)
            }
        }

        suspend fun Any.callSuspend(
            name: String,
            parameterTypes: Array<Class<*>> = emptyArray(),
            vararg args: Any?,
        ) = suspendCoroutine { continuation ->
            val method = javaClass.getDeclaredMethod(name, *parameterTypes, Continuation::class.java).apply {
                isAccessible = true
            }
            val result = try {
                method.invoke(this, *args, continuation)
            } catch (e: InvocationTargetException) {
                continuation.resumeWithException(checkNotNull(e.cause))
                return@suspendCoroutine
            } catch (e: Throwable) {
                continuation.resumeWithException(e)
                return@suspendCoroutine
            }
            if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
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
    }
}
