package be.mygod.librootkotlinx.impl

import android.os.ParcelFileDescriptor
import be.mygod.librootkotlinx.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Owns app-side resources for one detached root process: startup stdio, ownership rendezvous, and shutdown revocation.
 */
internal class RootProcessHandle(
    packageName: String,
    packageCodePath: String,
    niceName: String,
    codeCacheDir: () -> File,
    handoffAuthority: String,
    handoffToken: String,
    private val handleRootIo: suspend (ParcelFileDescriptor, ParcelFileDescriptor, ParcelFileDescriptor) -> Unit,
) : Closeable {
    private val ownership = RootProcessOwnership()
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
        val handlerCompletion = startRootIoHandler(this, pipes.takeHandlerStdio())
        try {
            launcher.launch(pipes)
            val ownershipAccepted = async { ownership.accept() }
            select {
                ownershipAccepted.onAwait { }
                handlerCompletion.onAwait {
                    currentCoroutineContext().ensureActive()
                    throw RootIoExitException(it)
                }
            }
            pipes.closeRemaining()

            awaitRootServiceConnected(rootServiceConnected, handlerCompletion)
        } finally {
            pipes.closeRemaining()
        }
    }

    override fun close() {
        ownership.close()
    }

    private fun startRootIoHandler(
        handlerScope: CoroutineScope,
        handlerStdio: RootProcessHandlerStdio,
    ): CompletableDeferred<Throwable?> {
        val handlerCompletion = CompletableDeferred<Throwable?>()
        handlerScope.launch {
            var failure: Throwable? = null
            try {
                handleRootIo(handlerStdio.stdin, handlerStdio.stdout, handlerStdio.stderr)
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                failure = e
                Logger.me.w("Root IO handling cancelled without cancelling root startup", e)
            } catch (e: Throwable) {
                failure = e
                Logger.me.w("Root IO handling failed", e)
            } finally {
                if (failure != null || currentCoroutineContext().isActive) handlerCompletion.complete(failure)
            }
        }.invokeOnCompletion {
            handlerStdio.close()
            if (it is CancellationException) {
                handlerCompletion.cancel(it)
            } else {
                handlerCompletion.complete(it)
            }
        }
        return handlerCompletion
    }

    internal companion object {
        suspend fun awaitRootServiceConnected(
            rootServiceConnected: Job,
            handlerCompletion: Deferred<Throwable?>,
        ) {
            var handlerCompleted = false
            var handlerFailure: Throwable? = null
            select {
                rootServiceConnected.onJoin { }
                handlerCompletion.onAwait {
                    handlerCompleted = true
                    handlerFailure = it
                }
            }
            if (handlerCompleted && !rootServiceConnected.isCompleted) {
                currentCoroutineContext().ensureActive()
                throw RootIoExitException(handlerFailure)
            }
            handlerCompletion.await()
        }
    }

    private class RootIoExitException(cause: Throwable?) :
        IOException("Root IO handling completed before root service connected", cause)
}
