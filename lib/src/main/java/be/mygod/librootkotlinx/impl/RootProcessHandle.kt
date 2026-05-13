package be.mygod.librootkotlinx.impl

import android.os.ParcelFileDescriptor
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.impl.libsu.RootProcessLauncher
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.io.Closeable
import java.io.IOException

/**
 * Owns the non-libsu side of one detached root process: startup stdio, ownership rendezvous, and shutdown revocation.
 */
internal class RootProcessHandle(
    packageCodePath: String,
    task: Shell.Task,
    private val handleRootIo: suspend (ParcelFileDescriptor, ParcelFileDescriptor, ParcelFileDescriptor) -> Unit,
) : Closeable {
    private val ownership = RootProcessOwnership()
    private val launcher = RootProcessLauncher(packageCodePath, task, ownership.socketName)

    suspend fun run(rootServiceConnected: Job) = coroutineScope {
        val stdio = RootProcessStdio()
        val handlerCompletion = startRootIoHandler(this, stdio.takeHandlerStdio())
        try {
            launcher.launch(stdio)
            val ownershipAccepted = async { ownership.accept() }
            select {
                ownershipAccepted.onAwait { }
                handlerCompletion.onAwait { throw RootIoExitException(it) }
            }
            stdio.closeRemaining()

            currentCoroutineContext().ensureActive()
            var handlerCompleted = false
            var handlerFailure: Throwable? = null
            select {
                rootServiceConnected.onJoin { }
                handlerCompletion.onAwait {
                    handlerCompleted = true
                    handlerFailure = it
                }
            }
            if (handlerCompleted && !rootServiceConnected.isCompleted) throw RootIoExitException(handlerFailure)
            handlerCompletion.await()
        } finally {
            stdio.closeRemaining()
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
            handlerCompletion.complete(it)
        }
        return handlerCompletion
    }

    private class RootIoExitException(cause: Throwable?) :
        IOException("Root IO handling completed before root service connected", cause)
}
