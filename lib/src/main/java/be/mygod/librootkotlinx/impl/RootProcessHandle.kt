package be.mygod.librootkotlinx.impl

import android.net.Credentials
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.RootProcess
import be.mygod.librootkotlinx.io.awaitExit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/**
 * Owns app-side resources for one root process: startup diagnostics, ownership rendezvous, and shutdown revocation.
 */
internal class RootProcessHandle(
    packageName: String,
    packageCodePath: String,
    niceName: String,
    codeCacheDir: () -> File,
    handoffAuthority: String,
    handoffToken: String,
    private val handleRootLifecycle: suspend (RootProcess) -> Unit,
) : Closeable {
    private val ownership = RootProcessOwnership()
    private val ownedProcess = AtomicReference<Process?>()
    private val launcher = RootProcessLauncher(
        packageName = packageName,
        packageCodePath = packageCodePath,
        niceName = niceName,
        codeCacheDir = codeCacheDir,
        ownershipSocketName = ownership.socketName,
        handoffAuthority = handoffAuthority,
        handoffToken = handoffToken,
    )

    suspend fun run(rootServiceConnected: Job, rootLifecycleCoroutineContext: CoroutineContext) = coroutineScope {
        val marker = RootProcessStartupMarker()
        var startupPipes: RootProcessPipes? = null
        try {
            val pipes = launcher.launch(marker).also {
                ownedProcess.set(it.process)
                startupPipes = it
            }
            val ownershipAccepted = async { ownership.accept() }
            val processExited = async { pipes.process.awaitExit() }
            val peerCredentials = try {
                awaitRootStartup(rootServiceConnected, ownershipAccepted, processExited, pipes::diagnosticsSuffix)
            } finally {
                ownershipAccepted.cancelAndJoin()
                processExited.cancelAndJoin()
            }
            launch(rootLifecycleCoroutineContext) {
                if (ownedProcess.getAndSet(null) == null) return@launch
                try {
                    handleRootLifecycle(RootProcess(pipes.process, peerCredentials,
                        pipes.stdin, pipes.stdout, pipes.stderr))
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                    Logger.me.w("Root lifecycle handling cancelled", e)
                } catch (e: Throwable) {
                    Logger.me.w("Root lifecycle handling failed", e)
                }
            }.invokeOnCompletion {
                pipes.closeStdio()
            }
            startupPipes = null
        } finally {
            withContext(NonCancellable) { startupPipes?.closeStdio() }
            marker.close()
        }
    }

    override fun close() {
        ownership.close()
        ownedProcess.getAndSet(null)?.destroy()
    }

    companion object {
        suspend fun awaitRootStartup(
            rootServiceConnected: Job,
            ownershipAccepted: Deferred<Credentials>,
            processExited: Deferred<Int>,
            diagnosticsSuffix: suspend () -> String,
        ): Credentials {
            val peerCredentials = select {
                ownershipAccepted.onAwait { it }
                processExited.onAwait { code ->
                    throw IOException(
                        "Root process exited with code $code before ownership accepted${diagnosticsSuffix()}")
                }
            }
            select {
                rootServiceConnected.onJoin { }
                processExited.onAwait { code ->
                    throw IOException(
                        "Root process exited with code $code before root service connected${diagnosticsSuffix()}")
                }
            }
            currentCoroutineContext().ensureActive()
            if (rootServiceConnected.isCancelled) rootServiceConnected.ensureActive()
            return peerCredentials
        }
    }
}
