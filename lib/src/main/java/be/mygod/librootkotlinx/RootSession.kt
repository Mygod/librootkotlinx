package be.mygod.librootkotlinx

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
 */
abstract class RootSession {
    protected abstract suspend fun initServer(server: RootServer)
    /**
     * Timeout to close [RootServer].
     */
    protected open val timeout get() = 5.minutes
    protected open val timeoutContext: CoroutineContext get() = Dispatchers.Default

    private val mutex = Mutex()
    private var server: RootServer? = null
    private var timeoutJob: Job? = null
    private var usersCount = 0L
    private var closePending = false

    suspend fun acquire() = mutex.withLock {
        haltTimeoutLocked()
        closePending = false
        server?.let {
            if (it.active) {
                ++usersCount
                return@withLock it
            }
            usersCount = 0
            withContext(NonCancellable) { closeLocked() }
        }
        check(usersCount == 0L) { "Unexpected $server, $usersCount" }
        val server = RootServer()
        try {
            initServer(server)
            this.server = server
            ++usersCount
            server
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
    @OptIn(DelicateCoroutinesApi::class)
    private fun startTimeoutLocked() {
        check(timeoutJob == null)
        timeoutJob = GlobalScope.launch(timeoutContext, CoroutineStart.UNDISPATCHED) {
            delay(timeout)
            mutex.withLock {
                ensureActive()
                check(usersCount == 0L)
                timeoutJob = null
                closeLocked()
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
        if (usersCount > 0) closePending = true else {
            haltTimeoutLocked()
            closeLocked()
        }
    }
}
