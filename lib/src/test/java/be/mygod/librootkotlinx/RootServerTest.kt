package be.mygod.librootkotlinx

import android.content.ComponentName
import android.content.ServiceConnection
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
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

        server.callback().onResponse(registered.id, RootCommandResponse(RootCommandResponse.SUCCESS, null))

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

        server.callback().onResponse(registered.id, RootCommandResponse(RootCommandResponse.SUCCESS, null))

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
    fun cleanupClosesQueuedConnectedService() = runTest {
        val server = RootServer()
        val service = RecordingRootCommandService()
        assertTrue(server.events().trySend(
            server.connectedEvent(NoOpServiceConnection, proxy(IBinder::class.java), service),
        ).isSuccess)
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

    private object NoOpOneWayCommand : RootCommandOneWay {
        override suspend fun execute() = Unit
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private fun RootServer.markConnected(service: IRootCommandService) {
        val connectedClass = RootServer::class.java.declaredClasses.single {
            it.simpleName == "ConnectedRootService"
        }
        val connected = connectedClass.declaredConstructors.single().let {
            it.isAccessible = true
            it.newInstance(NoOpServiceConnection, proxy(IBinder::class.java), service, null)
        }
        RootServer::class.java.getDeclaredField("connected").apply {
            isAccessible = true
            set(this@markConnected, connected)
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

    private fun RootServer.events(): Channel<Any> =
        RootServer::class.java.getDeclaredField("events").let {
            it.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            it.get(this) as Channel<Any>
        }

    private fun RootServer.connectedEvent(
        connection: ServiceConnection,
        binder: IBinder,
        service: IRootCommandService,
    ): Any {
        val eventClass = RootServer::class.java.declaredClasses.single { it.simpleName == "Event" }
            .declaredClasses.single { it.simpleName == "Connected" }
        return eventClass.declaredConstructors.single().let {
            it.isAccessible = true
            it.newInstance(connection, binder, service)
        }
    }

    private suspend fun RootServer.cleanup() = callSuspend("cleanup")

    private object NoOpServiceConnection : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    private companion object {
        suspend fun Any.callSuspend(name: String) = suspendCoroutine { continuation ->
            val method = javaClass.getDeclaredMethod(name, Continuation::class.java).apply { isAccessible = true }
            val result = try {
                method.invoke(this, continuation)
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
