package be.mygod.librootkotlinx.impl

import android.net.Credentials
import android.os.Handler
import android.os.Looper
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.RootProcess
import be.mygod.librootkotlinx.io.FileDescriptorByteReadChannel
import be.mygod.librootkotlinx.io.awaitExit
import be.mygod.librootkotlinx.io.openReadChannel
import be.mygod.librootkotlinx.io.useLines
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
        val pipes = RootProcessPipes()
        var handlerStdio: RootProcessHandlerStdio? = null
        val diagnosticChannels = ArrayList<FileDescriptorByteReadChannel>(2)
        val diagnosticDrains = ArrayList<Job>(2)
        suspend fun cancelDiagnostics() {
            withContext(NonCancellable) {
                for (drain in diagnosticDrains) drain.cancel()
                for (channel in diagnosticChannels) channel.cancel(null)
                for (drain in diagnosticDrains) drain.join()
            }
            diagnosticDrains.clear()
            diagnosticChannels.clear()
        }
        try {
            val stdio = pipes.takeHandlerStdio().also { handlerStdio = it }
            val handler = Handler(Looper.getMainLooper())
            for ((descriptor, log) in listOf(stdio.stdout to Logger.me::i, stdio.stderr to Logger.me::e)) {
                val channel = descriptor.dup().openReadChannel(handler)
                diagnosticChannels += channel
                diagnosticDrains += launch {
                    try {
                        channel.useLines { log(it, null) }
                    } catch (e: IOException) {
                        if (currentCoroutineContext().isActive) {
                            Logger.me.w("Root startup diagnostic drain failed", e)
                        }
                    }
                }
            }
            val process = launcher.launch(pipes).also { ownedProcess.set(it.process) }
            pipes.closeRemaining()
            val ownershipAccepted = async { ownership.accept() }
            val startupDiagnosticDrains = diagnosticDrains.toList()
            val startupStdioClosed = async {
                for (drain in startupDiagnosticDrains) drain.join()
            }
            val peerCredentials = try {
                awaitRootStartup(rootServiceConnected, ownershipAccepted, startupStdioClosed) {
                    process.process.awaitExit()
                }
            } finally {
                ownershipAccepted.cancelAndJoin()
                startupStdioClosed.cancelAndJoin()
            }
            cancelDiagnostics()
            launch(rootLifecycleCoroutineContext) {
                if (ownedProcess.getAndSet(null) == null) return@launch
                try {
                    handleRootLifecycle(
                        RootProcess(process.process, peerCredentials, stdio.stdin, stdio.stdout, stdio.stderr),
                    )
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                    Logger.me.w("Root lifecycle handling cancelled", e)
                } catch (e: Throwable) {
                    Logger.me.w("Root lifecycle handling failed", e)
                }
            }.invokeOnCompletion {
                stdio.close()
            }
            handlerStdio = null
        } finally {
            cancelDiagnostics()
            withContext(NonCancellable) { handlerStdio?.close() }
            pipes.closeRemaining()
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
            startupStdioClosed: Deferred<Unit>,
            awaitFailureExit: suspend () -> Int,
        ): Credentials {
            val peerCredentials = select<Credentials> {
                ownershipAccepted.onAwait { it }
                startupStdioClosed.onAwait {
                    val exitCode = awaitFailureExit()
                    throw IOException(
                        "Root process stdout/stderr closed before ownership accepted with exit code $exitCode")
                }
            }
            select<Unit> {
                rootServiceConnected.onJoin { }
                startupStdioClosed.onAwait {
                    val exitCode = awaitFailureExit()
                    throw IOException(
                        "Root process stdout/stderr closed before root service connected with exit code $exitCode")
                }
            }
            currentCoroutineContext().ensureActive()
            if (rootServiceConnected.isCancelled) rootServiceConnected.ensureActive()
            return peerCredentials
        }
    }
}
