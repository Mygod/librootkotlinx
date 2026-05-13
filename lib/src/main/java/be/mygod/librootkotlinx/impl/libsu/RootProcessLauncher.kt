package be.mygod.librootkotlinx.impl.libsu

import android.annotation.SuppressLint
import be.mygod.librootkotlinx.impl.RootProcessOwnership
import be.mygod.librootkotlinx.impl.RootProcessStdio
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import io.ktor.utils.io.readLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Adapts libsu's generated RootService startup command.
 *
 * This mirrors the command emitted by libsu's RootServiceManager.createBindTask. The intentional changes are:
 * replacing libsu's RootServerMain with [RootProcessMain] so stdout/stderr stay open, extending CLASSPATH with the app
 * APK so that entry point is visible, injecting [RootProcessOwnership.SOCKET_ENV] so the app can revoke the detached
 * root process, redirecting stdio to [RootProcessStdio], and using a marker pipe to confirm the redirected child was
 * actually started. App-side IO handling, ownership revocation, and RootServer lifecycle policy belong to
 * [be.mygod.librootkotlinx.impl.RootProcessHandle], not this class.
 */
@SuppressLint("RestrictedApi")
internal class RootProcessLauncher(
    private val packageCodePath: String,
    private val task: Shell.Task,
    private val ownershipSocketName: String,
) {
    suspend fun launch(stdio: RootProcessStdio) {
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
        awaitStartupMarker(stdio, startupNonce)
    }

    private fun createStartupCommand(stdio: RootProcessStdio, startupNonce: String): String {
        val command = ByteArrayOutputStream().also {
            task.run(it, EmptyInputStream, EmptyInputStream)
        }.toString(StandardCharsets.UTF_8.name()).trim()
        return rewriteCommand(
            command,
            packageCodePath,
            stdio.redirect,
            stdio.markerRedirect,
            startupNonce,
            ownershipSocketName,
        )
    }

    private suspend fun awaitStartupMarker(stdio: RootProcessStdio, startupNonce: String) {
        val channel = stdio.openMarkerReadChannel()
        try {
            while (true) when (channel.readLine()) {
                startupMarker(STARTUP_MARKER_STARTED, startupNonce) -> return
                startupMarker(STARTUP_MARKER_FAILED, startupNonce) -> {
                    throw IOException("Root service stdio redirection failed")
                }
                null -> throw IOException("Root service startup marker pipe closed")
            }
        } finally {
            channel.cancel(null)
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
            val withOwnership = "${RootProcessOwnership.SOCKET_ENV}=" +
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

        private const val STARTUP_MARKER_STARTED = "librootkotlinx-started:"
        private const val STARTUP_MARKER_FAILED = "librootkotlinx-failed:"

        private fun startupMarker(prefix: String, nonce: String) = prefix + nonce
    }
}
