package be.mygod.librootkotlinx.impl

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.io.FileDescriptorByteReadChannel
import be.mygod.librootkotlinx.io.openReadChannel
import be.mygod.librootkotlinx.io.useLines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    private val handleRootLifecycle: suspend (
        Process,
        ParcelFileDescriptor,
        ParcelFileDescriptor,
        ParcelFileDescriptor,
    ) -> Unit,
) : Closeable {
    private val ownership = RootProcessOwnership()
    @Volatile
    private var process: Process? = null
    private val launcher = RootProcessLauncher(
        packageName = packageName,
        packageCodePath = packageCodePath,
        niceName = niceName,
        codeCacheDir = codeCacheDir,
        ownershipSocketName = ownership.socketName,
        handoffAuthority = handoffAuthority,
        handoffToken = handoffToken,
    )

    suspend fun run(rootServiceConnected: Job) = coroutineScope {
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
            val process = launcher.launch(pipes).also { this@RootProcessHandle.process = it.process }
            pipes.closeRemaining()
            val ownershipAccepted = async { ownership.accept() }
            val startupDiagnosticDrains = diagnosticDrains.toList()
            val startupStdioClosed = async {
                for (drain in startupDiagnosticDrains) drain.join()
            }
            try {
                awaitRootStartup(rootServiceConnected, ownershipAccepted, startupStdioClosed, process::awaitExit)
            } finally {
                ownershipAccepted.cancelAndJoin()
                startupStdioClosed.cancelAndJoin()
            }
            cancelDiagnostics()
            val handlerCompletion = CompletableDeferred<Throwable?>()
            launch {
                var failure: Throwable? = null
                try {
                    handleRootLifecycle(process.process, stdio.stdin, stdio.stdout, stdio.stderr)
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                    failure = e
                    Logger.me.w("Root lifecycle handling cancelled", e)
                } catch (e: Throwable) {
                    failure = e
                    Logger.me.w("Root lifecycle handling failed", e)
                } finally {
                    if (failure != null || currentCoroutineContext().isActive) handlerCompletion.complete(failure)
                }
            }.invokeOnCompletion {
                stdio.close()
                if (it is CancellationException) {
                    handlerCompletion.cancel(it)
                } else handlerCompletion.complete(it)
            }
            handlerStdio = null
            handlerCompletion.await()
        } finally {
            cancelDiagnostics()
            withContext(NonCancellable) { handlerStdio?.close() }
            pipes.closeRemaining()
        }
    }

    override fun close() {
        ownership.close()
        process?.destroy()
    }

    companion object {
        suspend fun awaitRootStartup(
            rootServiceConnected: Job,
            ownershipAccepted: Deferred<Unit>,
            startupStdioClosed: Deferred<Unit>,
            awaitFailureExit: suspend () -> Int,
        ) {
            select {
                ownershipAccepted.onAwait { }
                startupStdioClosed.onAwait {
                    val exitCode = awaitFailureExit()
                    throw IOException(
                        "Root process stdout/stderr closed before ownership accepted with exit code $exitCode")
                }
            }
            select {
                rootServiceConnected.onJoin { }
                startupStdioClosed.onAwait {
                    val exitCode = awaitFailureExit()
                    throw IOException(
                        "Root process stdout/stderr closed before root service connected with exit code $exitCode")
                }
            }
            currentCoroutineContext().ensureActive()
            if (rootServiceConnected.isCancelled) throw CancellationException("Root startup cancelled")
        }
    }
}
