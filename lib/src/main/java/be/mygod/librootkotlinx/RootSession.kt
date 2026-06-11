package be.mygod.librootkotlinx

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.CallSuper
import be.mygod.librootkotlinx.io.useLines
import be.mygod.librootkotlinx.io.openReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.minutes

/**
 * This object manages creation of [RootServer] and times them out automagically, with default timeout of 5 minutes.
 *
 * Use one process-wide [RootSession] for the root command service. Concurrent clients should share it through [acquire],
 * [use], and [release] instead of creating multiple sessions.
 */
abstract class RootSession {
    protected abstract val context: Context

    /**
     * Name passed to root app_process through --nice-name.
     */
    protected open val niceName get() = "${context.packageName}:librootkotlinx:${android.os.Process.myUid() / 100000}"

    /**
     * Handles the root app_process lifecycle and observed stdin/stdout/stderr.
     *
     * This is called after the root command service has connected. Keep it suspended while the descriptors should
     * stay open. Returning only ends lifecycle handling.
     *
     * The [rootProcess] includes the local `su` [Process], raw ownership-socket peer credentials, and stdio descriptors.
     * Its [RootProcess.process] exit status is best-effort diagnostic data; daemon/proxy `su` implementations can
     * synthesize it instead of reporting the root JVM's real exit status or signal.
     */
    protected open suspend fun handleRootLifecycle(rootProcess: RootProcess) {
        rootProcess.stdin.close()
        val handler = Handler(Looper.getMainLooper())
        coroutineScope {
            launch { rootProcess.stdout.openReadChannel(handler).useLines(Logger.me::i) }
            launch { rootProcess.stderr.openReadChannel(handler).useLines(Logger.me::e) }
        }
    }

    /**
     * Coroutine context for [handleRootLifecycle].
     *
     * The default returns a fresh [SupervisorJob] for each root startup so lifecycle handling can finish independently
     * from [RootServer.close]. Override with a context without a [Job], such as `EmptyCoroutineContext`, to make
     * lifecycle handling inherit the server scope and participate in structured close cleanup. Overrides that include
     * a [Job] should provide a fresh usable job for each startup.
     */
    protected open val rootLifecycleCoroutineContext: CoroutineContext get() = SupervisorJob()

    @CallSuper
    protected open suspend fun initServer(server: RootServer) =
        server.init(context, niceName, rootLifecycleCoroutineContext, ::handleRootLifecycle)

    /**
     * Timeout to close [RootServer].
     */
    protected open val timeout get() = 5.minutes
    protected open val timeoutDispatcher: CoroutineDispatcher get() = Dispatchers.Default

    private val mutex = Mutex()
    private val timeoutScope by lazy { CoroutineScope(SupervisorJob() + timeoutDispatcher) }
    private var server: RootServer? = null
    private var startupBarrier: CompletableDeferred<Unit>? = null
    private var timeoutJob: Job? = null
    private var leaseCount = 0L
    private var closeOnRelease = false

    @Throws(NoShellException::class)
    suspend fun acquire(): RootServer {
        while (true) {
            var activeServer: RootServer? = null
            var startupBarrierToAwait: CompletableDeferred<Unit>? = null
            var shouldStart = false
            mutex.withLock {
                server?.let {
                    haltTimeoutLocked()
                    if (it.active) {
                        ++leaseCount
                        activeServer = it
                        return@withLock
                    }
                    leaseCount = 0
                    withContext(NonCancellable) { closeLocked() }
                }
                check(leaseCount == 0L) { "Unexpected $server, $leaseCount" }
                startupBarrier?.let { startupBarrierToAwait = it } ?: run {
                    haltTimeoutLocked()
                    closeOnRelease = false
                    startupBarrier = CompletableDeferred()
                    shouldStart = true
                }
            }
            activeServer?.let { return it }
            startupBarrierToAwait?.await()
            if (shouldStart) return startServer()
        }
    }

    private suspend fun startServer(): RootServer {
        var created: RootServer? = null
        try {
            val server = createServer()
            created = server
            val startupBarrier = mutex.withLock {
                check(this.server == null && leaseCount == 0L) { "Unexpected ${this.server}, $leaseCount" }
                this.server = server
                ++leaseCount
                created = null
                startupBarrier.also { startupBarrier = null }
            }
            startupBarrier?.complete(Unit)
            return server
        } catch (e: Throwable) {
            val startupBarrier = withContext(NonCancellable) {
                created?.let {
                    try {
                        it.close()
                    } catch (eClose: Throwable) {
                        e.addSuppressed(eClose)
                    }
                }
                mutex.withLock { startupBarrier.also { startupBarrier = null } }
            }
            if (e is CancellationException) {
                startupBarrier?.complete(Unit)
            } else {
                startupBarrier?.completeExceptionally(e)
            }
            throw e
        }
    }

    private suspend fun createServer(): RootServer {
        val server = RootServer()
        try {
            initServer(server)
            currentCoroutineContext().ensureActive()
            return server
        } catch (e: Throwable) {
            try {
                withContext(NonCancellable) { server.close() }
            } catch (eClose: Throwable) {
                e.addSuppressed(eClose)
            }
            throw e
        }
    }

    private suspend fun closeLocked() {
        closeOnRelease = false
        val server = server
        this.server = null
        server?.close()
    }
    private fun startTimeoutLocked() {
        check(timeoutJob == null)
        timeoutJob = timeoutScope.launch(start = CoroutineStart.UNDISPATCHED) {
            delay(timeout)
            mutex.withLock {
                // Mutex.lock does not check cancellation when it takes the uncontended fast path.
                ensureActive()
                check(leaseCount == 0L)
                timeoutJob = null
                try {
                    closeLocked()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logger.me.w("Root server timeout close failed", e)
                }
            }
        }
    }
    private fun haltTimeoutLocked() {
        timeoutJob?.cancel()
        timeoutJob = null
    }
    suspend fun release(server: RootServer) = withContext(NonCancellable) {
        mutex.withLock {
            if (this@RootSession.server != server) return@withLock  // outdated reference
            require(leaseCount > 0)
            when {
                !server.active -> {
                    leaseCount = 0
                    closeLocked()
                    return@withLock
                }
                --leaseCount > 0L -> return@withLock
                closeOnRelease -> closeLocked()
                else -> startTimeoutLocked()
            }
        }
    }
    @Throws(NoShellException::class)
    suspend inline fun <T> use(block: (RootServer) -> T): T {
        val server = acquire()
        try {
            return block(server)
        } finally {
            release(server)
        }
    }

    suspend fun closeExisting() = mutex.withLock {
        if (leaseCount > 0 || startupBarrier != null) closeOnRelease = true else {
            haltTimeoutLocked()
            closeLocked()
        }
    }

}
