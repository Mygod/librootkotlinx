package be.mygod.librootkotlinx.impl

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.io.openReadChannel
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Starts libsu's generated RootService command while keeping root process stdio observable.
 */
@SuppressLint("RestrictedApi")
internal class RootProcessLauncher(
    private val context: Context,
    private val task: Shell.Task,
    private val handleRootIo: suspend (ParcelFileDescriptor, ParcelFileDescriptor, ParcelFileDescriptor) -> Unit,
    private val ownershipSocketName: String,
) {
    suspend fun run(
        rootServiceConnected: Job,
        awaitStartupOwnership: suspend () -> Unit = {},
    ) = coroutineScope {
        val eventHandler = Handler(Looper.getMainLooper())
        val stdio = Stdio()
        val handlerStdio = stdio.takeHandlerStdio()
        val handlerCompletion = startRootIoHandler(this, handlerStdio)
        try {
            val shell = runInterruptible(Dispatchers.IO) { Shell.getShell() }
            if (!shell.isRoot) throw NoShellException("Root shell is not available")

            val startupNonce = UUID.randomUUID().toString()
            runInterruptible(Dispatchers.IO) {
                val command = createStartupCommand(stdio, startupNonce)
                // Job.exec waits for the foreground wrapper to open fd 3 before the app closes its marker writer.
                val result = shell.newJob().add(command).to(null, null).exec()
                if (result.code == Shell.Result.JOB_NOT_EXECUTED) {
                    throw NoShellException("Root shell died before starting root service")
                }
                if (!result.isSuccess) throw IOException("Root service startup command failed with exit code ${result.code}")
            }
            stdio.closeMarkerWrite()
            awaitStartupMarker(stdio, startupNonce, eventHandler)
            val ownershipAccepted = async { awaitStartupOwnership() }
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

    private fun startRootIoHandler(
        handlerScope: CoroutineScope,
        handlerStdio: HandlerStdio,
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

    private fun createStartupCommand(stdio: Stdio, startupNonce: String): String {
        val command = ByteArrayOutputStream().also {
            task.run(it, EmptyInputStream, EmptyInputStream)
        }.toString(StandardCharsets.UTF_8.name()).trim()
        return rewriteCommand(
            command,
            context.packageCodePath,
            stdio.redirect,
            stdio.markerRedirect,
            startupNonce,
            ownershipSocketName,
        )
    }

    private suspend fun awaitStartupMarker(stdio: Stdio, startupNonce: String, eventHandler: Handler) {
        val channel = stdio.openMarkerReadChannel(eventHandler)
        val success = startupMarker(STARTUP_MARKER_STARTED, startupNonce)
        val failed = startupMarker(STARTUP_MARKER_FAILED, startupNonce)
        try {
            while (true) when (val line = channel.readLine()) {
                success -> return
                failed -> throw IOException("Root service stdio redirection failed")
                null -> throw IOException("Root service startup marker pipe closed")
            }
        } finally {
            channel.cancel(null)
        }
    }

    private class RootIoExitException(cause: Throwable?) :
        IOException("Root IO handling completed before root service connected", cause)

    private data class HandlerStdio(
        val stdin: ParcelFileDescriptor,
        val stdout: ParcelFileDescriptor,
        val stderr: ParcelFileDescriptor,
    ) : Closeable {
        override fun close() {
            close(stdin)
            close(stdout)
            close(stderr)
        }
    }

    private class Stdio {
        private var stdinRead: ParcelFileDescriptor?
        private var stdinWrite: ParcelFileDescriptor?
        private var stdoutRead: ParcelFileDescriptor?
        private var stdoutWrite: ParcelFileDescriptor?
        private var stderrRead: ParcelFileDescriptor?
        private var stderrWrite: ParcelFileDescriptor?
        private var markerRead: ParcelFileDescriptor?
        private var markerWrite: ParcelFileDescriptor?

        val redirect get() = " <${fdPath(checkNotNull(stdinRead))}" +
                " >${fdPath(checkNotNull(stdoutWrite))}" +
                " 2>${fdPath(checkNotNull(stderrWrite))}"
        val markerRedirect get() = fdPath(checkNotNull(markerWrite))

        init {
            val stdin = ParcelFileDescriptor.createPipe()
            stdinRead = stdin[0]
            stdinWrite = stdin[1]
            val stdout = ParcelFileDescriptor.createPipe()
            stdoutRead = stdout[0]
            stdoutWrite = stdout[1]
            val stderr = ParcelFileDescriptor.createPipe()
            stderrRead = stderr[0]
            stderrWrite = stderr[1]
            val marker = ParcelFileDescriptor.createPipe()
            markerRead = marker[0]
            markerWrite = marker[1]
        }

        fun takeHandlerStdio() = HandlerStdio(
            checkNotNull(stdinWrite),
            checkNotNull(stdoutRead),
            checkNotNull(stderrRead),
        ).also {
            stdinWrite = null
            stdoutRead = null
            stderrRead = null
        }

        fun openMarkerReadChannel(eventHandler: Handler) =
            checkNotNull(markerRead).openReadChannel(eventHandler).also { markerRead = null }

        fun closeMarkerWrite() {
            close(markerWrite)
            markerWrite = null
        }

        fun closeRemaining() {
            close(stdinRead)
            close(stdinWrite)
            close(stdoutRead)
            close(stdoutWrite)
            close(stderrRead)
            close(stderrWrite)
            close(markerRead)
            close(markerWrite)
            stdinRead = null
            stdinWrite = null
            stdoutRead = null
            stdoutWrite = null
            stderrRead = null
            stderrWrite = null
            markerRead = null
            markerWrite = null
        }
    }

    private object EmptyInputStream : InputStream() {
        override fun read() = -1
    }

    companion object {
        private const val DEV_NULL_REDIRECT = " >/dev/null 2>&1"
        private const val STOCK_SUFFIX = "$DEV_NULL_REDIRECT)&"
        private const val CLASSPATH = "CLASSPATH="

        internal fun rewriteCommand(
            command: String,
            packageCodePath: String,
            stdioRedirect: String,
            markerRedirect: String,
            startupNonce: String,
            ownershipSocketName: String,
        ): String {
            // Mirrors the command shape emitted by libsu RootServiceManager.createBindTask:
            // "(... RootServerMain ... >/dev/null 2>&1)&".
            // We keep libsu's detached child startup model and rewrite only the root-side entry point, classpath,
            // ownership socket environment, stdio redirection, and a marker pipe for auditing startup redirection. The
            // fixed fd 3 is opened only inside the temporary foreground subshell below, so it does not clobber libsu's
            // cached shell state. If libsu changes the format, fail here instead of silently launching wrong.
            // https://github.com/topjohnwu/libsu/blob/8314fa2f48b8421cdb1d38c472c518167b1cba9d/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L227-L233
            check(command.startsWith("(") && command.endsWith(STOCK_SUFFIX)) {
                "Unexpected libsu RootService startup command: $command"
            }
            val foreground = command.substring(1, command.length - STOCK_SUFFIX.length)
            val withMain = foreground.replace(" com.topjohnwu.superuser.internal.RootServerMain ",
                " ${RootProcessMain::class.java.name} ")
            check(withMain != foreground) {
                "Unexpected libsu RootService main class in startup command: $command"
            }
            val withOwnership = "${RootProcessMain.OWNERSHIP_SOCKET_ENV}=" +
                    "${ShellUtils.escapedString(ownershipSocketName)} $withMain"
            val start = withOwnership.indexOf(CLASSPATH)
            check(start >= 0) { "Missing CLASSPATH in libsu RootService startup command: $withOwnership" }
            val valueStart = start + CLASSPATH.length
            val valueEnd = withOwnership.indexOf(' ', valueStart).takeIf { it >= 0 } ?: withOwnership.length
            val value = withOwnership.substring(valueStart, valueEnd)
            val classPath = ShellUtils.escapedString("$value:$packageCodePath")
            val observedCommand = withOwnership.replaceRange(valueStart, valueEnd, classPath)
            var commandStart = valueStart + classPath.length
            while (commandStart < observedCommand.length && observedCommand[commandStart] == ' ') ++commandStart
            val execCommand = observedCommand.replaceRange(commandStart, commandStart, "exec ")
            val success = ShellUtils.escapedString(startupMarker(STARTUP_MARKER_STARTED, startupNonce))
            val failed = ShellUtils.escapedString(startupMarker(STARTUP_MARKER_FAILED, startupNonce))
            return "(if command exec 3>$markerRedirect; then " +
                    "(if command exec$stdioRedirect; then " +
                    "printf '%s\\n' $success >&3; " +
                    "exec 3>&-; " +
                    "$execCommand; " +
                    "else printf '%s\\n' $failed >&3; exit 1; fi)& " +
                    "command exec 3>&-; else exit 1; fi)"
        }

        internal fun appFdPath(fd: Int, pid: Int = Process.myPid()) = ShellUtils.escapedString("/proc/$pid/fd/$fd")

        private fun fdPath(descriptor: ParcelFileDescriptor) = appFdPath(descriptor.fd)

        private const val STARTUP_MARKER_STARTED = "librootkotlinx-started:"
        private const val STARTUP_MARKER_FAILED = "librootkotlinx-failed:"

        private fun startupMarker(prefix: String, nonce: String) = prefix + nonce

        private fun close(closeable: Closeable?) {
            if (closeable != null) try {
                closeable.close()
            } catch (e: IOException) {
                Logger.me.w("Failed to close root process resource", e)
            }
        }
    }
}
