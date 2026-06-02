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
        var stdoutDiagnostic: FileDescriptorByteReadChannel? = null
        var stderrDiagnostic: FileDescriptorByteReadChannel? = null
        var stdoutDrain: Job? = null
        var stderrDrain: Job? = null
        try {
            val stdio = pipes.takeHandlerStdio().also { handlerStdio = it }
            val handler = Handler(Looper.getMainLooper())
            val stdoutDiagnosticChannel = stdio.stdout.dup().openReadChannel(handler)
            stdoutDiagnostic = stdoutDiagnosticChannel
            val stderrDiagnosticChannel = stdio.stderr.dup().openReadChannel(handler)
            stderrDiagnostic = stderrDiagnosticChannel
            val stdoutDrainJob = launch {
                try {
                    stdoutDiagnosticChannel.useLines(Logger.me::i)
                } catch (e: IOException) {
                    if (currentCoroutineContext().isActive) Logger.me.w("Root startup diagnostic drain failed", e)
                }
            }
            val stderrDrainJob = launch {
                try {
                    stderrDiagnosticChannel.useLines(Logger.me::e)
                } catch (e: IOException) {
                    if (currentCoroutineContext().isActive) Logger.me.w("Root startup diagnostic drain failed", e)
                }
            }
            stdoutDrain = stdoutDrainJob
            stderrDrain = stderrDrainJob
            val process = launcher.launch(pipes).also { this@RootProcessHandle.process = it.process }
            pipes.closeRemaining()
            val ownershipAccepted = async { ownership.accept() }
            val startupStdioClosed = async {
                stdoutDrainJob.join()
                stderrDrainJob.join()
            }
            try {
                awaitRootStartup(rootServiceConnected, ownershipAccepted, startupStdioClosed, process::awaitExit)
            } finally {
                ownershipAccepted.cancelAndJoin()
                startupStdioClosed.cancelAndJoin()
            }
            withContext(NonCancellable) {
                stdoutDrainJob.cancel()
                stderrDrainJob.cancel()
                stdoutDiagnosticChannel.cancel(null)
                stderrDiagnosticChannel.cancel(null)
                stdoutDrainJob.join()
                stderrDrainJob.join()
            }
            stdoutDrain = null
            stderrDrain = null
            stdoutDiagnostic = null
            stderrDiagnostic = null
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
                } else {
                    handlerCompletion.complete(it)
                }
            }
            handlerStdio = null
            handlerCompletion.await()
        } finally {
            withContext(NonCancellable) {
                stdoutDrain?.cancel()
                stderrDrain?.cancel()
                stdoutDiagnostic?.cancel(null)
                stderrDiagnostic?.cancel(null)
                stdoutDrain?.join()
                stderrDrain?.join()
                handlerStdio?.close()
            }
            pipes.closeRemaining()
        }
    }

    override fun close() {
        ownership.close()
        process?.destroy()
    }

    internal companion object {
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
                    throw IOException("Root process stdout/stderr closed before ownership accepted with exit code $exitCode")
                }
            }
            select {
                rootServiceConnected.onJoin { }
                startupStdioClosed.onAwait {
                    val exitCode = awaitFailureExit()
                    throw IOException("Root process stdout/stderr closed before root service connected with exit code $exitCode")
                }
            }
            currentCoroutineContext().ensureActive()
            if (rootServiceConnected.isCancelled) throw CancellationException("Root startup cancelled")
        }
    }
}
