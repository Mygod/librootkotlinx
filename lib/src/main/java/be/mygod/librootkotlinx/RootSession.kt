package be.mygod.librootkotlinx

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.annotation.CallSuper
import be.mygod.librootkotlinx.io.forEachLine
import be.mygod.librootkotlinx.io.openReadChannel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
     * Handles observed stdin/stdout/stderr of the root app_process.
     *
     * Keep this suspended while the descriptors should stay open. Returning or failing before startup completes makes
     * startup fail; after startup, returning only ends IO handling.
     */
    protected open suspend fun handleRootIo(
        stdin: ParcelFileDescriptor,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
    ) {
        stdin.close()
        val handler = Handler(Looper.getMainLooper())
        coroutineScope {
            launch { stdout.openReadChannel(handler).forEachLine(Logger.me::i) }
            launch { stderr.openReadChannel(handler).forEachLine(Logger.me::e) }
        }
    }

    @CallSuper
    protected open suspend fun initServer(server: RootServer) {
        server.init(context, ::handleRootIo)
    }

    /**
     * Timeout to close [RootServer].
     */
    protected open val timeout get() = 5.minutes
    protected open val timeoutDispatcher: CoroutineDispatcher get() = Dispatchers.Default

    private val mutex = Mutex()
    private val timeoutScope by lazy { CoroutineScope(SupervisorJob() + timeoutDispatcher) }
    private var server: RootServer? = null
    private var startup: CompletableDeferred<Unit>? = null
    private var timeoutJob: Job? = null
    private var usersCount = 0L
    private var closePending = false

    suspend fun acquire(): RootServer {
        while (true) {
            var activeServer: RootServer? = null
            var startupToAwait: CompletableDeferred<Unit>? = null
            var shouldStart = false
            mutex.withLock {
                server?.let {
                    haltTimeoutLocked()
                    if (it.active) {
                        ++usersCount
                        activeServer = it
                        return@withLock
                    }
                    usersCount = 0
                    withContext(NonCancellable) { closeLocked() }
                }
                check(usersCount == 0L) { "Unexpected $server, $usersCount" }
                startup?.let { startupToAwait = it } ?: run {
                    haltTimeoutLocked()
                    closePending = false
                    startup = CompletableDeferred()
                    shouldStart = true
                }
            }
            activeServer?.let { return it }
            startupToAwait?.await()
            if (shouldStart) return startServer()
        }
    }

    private suspend fun startServer(): RootServer {
        var created: RootServer? = null
        try {
            val server = createServer()
            created = server
            val startup = mutex.withLock {
                check(this.server == null && usersCount == 0L) { "Unexpected ${this.server}, $usersCount" }
                this.server = server
                ++usersCount
                created = null
                finishStartupLocked()
            }
            startup?.complete(Unit)
            return server
        } catch (e: Throwable) {
            val startup = withContext(NonCancellable) {
                created?.let {
                    try {
                        it.close()
                    } catch (eClose: Throwable) {
                        e.addSuppressed(eClose)
                    }
                }
                mutex.withLock { finishStartupLocked() }
            }
            if (e is CancellationException) {
                startup?.complete(Unit)
            } else {
                startup?.completeExceptionally(e)
            }
            throw e
        }
    }

    private fun finishStartupLocked() = startup.also { startup = null }

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
        closePending = false
        val server = server
        this.server = null
        server?.close()
    }
    private fun startTimeoutLocked() {
        check(timeoutJob == null)
        timeoutJob = timeoutScope.launch(start = CoroutineStart.UNDISPATCHED) {
            delay(timeout)
            mutex.withLock {
                ensureActive()
                check(usersCount == 0L)
                timeoutJob = null
                try {
                    closeLocked()
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
            require(usersCount > 0)
            when {
                !server.active -> {
                    usersCount = 0
                    closeLocked()
                    return@withLock
                }
                --usersCount > 0L -> return@withLock
                closePending -> closeLocked()
                else -> startTimeoutLocked()
            }
        }
    }
    suspend inline fun <T> use(block: (RootServer) -> T): T {
        val server = acquire()
        try {
            return block(server)
        } finally {
            release(server)
        }
    }

    suspend fun closeExisting() = mutex.withLock {
        if (usersCount > 0 || startup != null) closePending = true else {
            haltTimeoutLocked()
            closeLocked()
        }
    }
}
