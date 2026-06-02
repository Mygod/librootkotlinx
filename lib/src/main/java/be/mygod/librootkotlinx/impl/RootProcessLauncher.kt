package be.mygod.librootkotlinx.impl

import android.os.Build
import android.os.Handler
import android.os.Looper
import be.mygod.librootkotlinx.NoShellException
import be.mygod.librootkotlinx.io.ProcessPipes
import be.mygod.librootkotlinx.io.ProcessPipes.Companion.startPipes
import be.mygod.librootkotlinx.io.openReadChannel
import be.mygod.librootkotlinx.io.openWriteChannel
import be.mygod.librootkotlinx.io.useLines
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/**
 * Builds and executes the root shell command that turns the shell process into the owned root app_process.
 *
 * This owns libsu RootServiceManager's app_process command contract instead of rewriting libsu's generated command.
 * The command keeps the base APK as the initial bootstrap classpath entry, redirects app_process stdio to app-owned
 * pipes, and writes a marker to a dedicated marker pipe after setup succeeds and immediately before app_process.
 *
 * libsu source:
 * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L191-L233
 */
internal class RootProcessLauncher(
    private val packageName: String,
    private val packageCodePath: String,
    private val niceName: String,
    private val codeCacheDir: () -> File,
    private val ownershipSocketName: String,
    private val handoffAuthority: String,
    private val handoffToken: String,
) {
    suspend fun launch(pipes: RootProcessPipes): ProcessPipes {
        val rootShell = executeRootShell(
            buildStartupCommand(
                packageName = packageName,
                packageCodePath = packageCodePath,
                niceName = niceName,
                stdinPath = pipes.stdinPath,
                stdoutPath = pipes.stdoutPath,
                stderrPath = pipes.stderrPath,
                markerPath = pipes.markerPath,
                ownershipSocketName = ownershipSocketName,
                handoffAuthority = handoffAuthority,
                handoffToken = handoffToken,
                appProcess = RootProcessAppProcess.myExe,
                shouldRelocate = Build.VERSION.SDK_INT < 26,
                relocationToken = if (Build.VERSION.SDK_INT < 26) relocationToken() else "",
            ),
        )
        var startupComplete = false
        val channel = pipes.openMarkerReadChannel()
        val diagnostics = StringBuilder()
        val diagnosticsSuffix = {
            synchronized(diagnostics) {
                val trimmed = diagnostics.toString().trim()
                if (trimmed.isEmpty()) "" else ": $trimmed"
            }
        }
        try {
            var shellOutputChannel: ByteReadChannel? = null
            var shellErrorChannel: ByteReadChannel? = null
            try {
                val handler = Handler(Looper.getMainLooper())
                val shellOutputPipe = checkNotNull(rootShell.stdout) { "Root shell stdout pipe was not requested" }
                    .openReadChannel(handler)
                shellOutputChannel = shellOutputPipe
                val shellErrorPipe = checkNotNull(rootShell.stderr) { "Root shell stderr pipe was not requested" }
                    .openReadChannel(handler)
                shellErrorChannel = shellErrorPipe
                coroutineScope {
                    val shellOutput = async { shellOutputPipe.drainStartupDiagnostics(diagnostics) }
                    val shellError = async { shellErrorPipe.drainStartupDiagnostics(diagnostics) }
                    val diagnosticsClosed = async { listOfNotNull(shellOutput.await(), shellError.await()) }
                    val marker = async { channel.readLine() }
                    try {
                        select<Unit> {
                            marker.onAwait { line ->
                                when (line) {
                                    STARTUP_MARKER_STARTED -> startupComplete = true
                                    null -> throw NoShellException(
                                        "Root service startup marker pipe closed${diagnosticsSuffix()}",
                                    )
                                    else -> throw NoShellException(
                                        "Unexpected root service startup marker: $line${diagnosticsSuffix()}",
                                    )
                                }
                            }
                            diagnosticsClosed.onAwait { drainFailures ->
                                pipes.closeMarkerWrite()
                                when (val line = marker.await()) {
                                    STARTUP_MARKER_STARTED -> startupComplete = true
                                    null -> {
                                        val exitCode = rootShell.awaitExit()
                                        throw NoShellException(
                                            "Root shell exited before root service startup with exit code $exitCode${
                                                diagnosticsSuffix()}",
                                        ).also { failure -> drainFailures.forEach(failure::addSuppressed) }
                                    }
                                    else -> throw NoShellException(
                                        "Unexpected root service startup marker: $line${diagnosticsSuffix()}",
                                    ).also { failure -> drainFailures.forEach(failure::addSuppressed) }
                                }
                            }
                        }
                    } finally {
                        pipes.closeMarkerWrite()
                        shellOutputPipe.cancel(null)
                        shellErrorPipe.cancel(null)
                        marker.cancelAndJoin()
                        diagnosticsClosed.cancelAndJoin()
                        shellOutput.cancelAndJoin()
                        shellError.cancelAndJoin()
                    }
                }
            } catch (e: IOException) {
                throw NoShellException("Root service startup marker read failed", e)
            } finally {
                shellOutputChannel?.cancel(null)
                shellErrorChannel?.cancel(null)
                channel.cancel(null)
            }
            return rootShell
        } finally {
            if (!startupComplete) rootShell.close()
        }
    }

    private suspend fun ByteReadChannel.drainStartupDiagnostics(diagnostics: StringBuilder): IOException? {
        return try {
            useLines { line ->
                synchronized(diagnostics) {
                    diagnostics.appendLine(line)
                    if (diagnostics.length > MAX_STARTUP_DIAGNOSTICS_LENGTH) {
                        diagnostics.delete(0, diagnostics.length - MAX_STARTUP_DIAGNOSTICS_LENGTH)
                    }
                }
            }
            null
        } catch (e: IOException) {
            e
        }
    }

    private suspend fun executeRootShell(command: String): ProcessPipes {
        val process = startRootShell()
        var input: ByteWriteChannel? = null
        try {
            val channel = checkNotNull(process.stdin) { "Root shell stdin pipe was not requested" }
                .openWriteChannel(Handler(Looper.getMainLooper()))
            input = channel
            channel.writeFully(command.encodeToByteArray())
            channel.flushAndClose()
            return process
        } catch (e: IOException) {
            input?.cancel(e)
            process.close()
            throw NoShellException("Root shell died before root service startup", e)
        } catch (e: InterruptedException) {
            input?.cancel(e)
            process.close()
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Throwable) {
            input?.cancel(e)
            process.close()
            throw e
        }
    }

    private fun startRootShell(): ProcessPipes {
        var failure: IOException? = null
        for (command in SU_COMMANDS) try {
            return ProcessBuilder(command).startPipes()
        } catch (e: IOException) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        throw NoShellException("Root shell is not available", checkNotNull(failure))
    }

    private fun relocationToken(): String {
        val persistence = File(codeCacheDir(), ".librootkotlinx-uuid")
        val uuid = try {
            persistence.readText()
        } catch (_: FileNotFoundException) {
            UUID.randomUUID().toString().also {
                persistence.parentFile?.mkdirs()
                persistence.writeText(it)
            }
        }
        return "$packageName@$uuid"
    }

    companion object {
        private val SU_COMMANDS = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "su",
        )
        private const val STARTUP_MARKER_STARTED = "librootkotlinx-started"
        private const val MAX_STARTUP_DIAGNOSTICS_LENGTH = 8192

        fun buildStartupCommand(
            packageName: String,
            packageCodePath: String,
            niceName: String,
            stdinPath: String,
            stdoutPath: String,
            stderrPath: String,
            markerPath: String,
            ownershipSocketName: String,
            handoffAuthority: String,
            handoffToken: String,
            appProcess: String,
            shouldRelocate: Boolean,
            relocationToken: String,
        ): String {
            val (relocationScript, executable) = if (shouldRelocate) {
                RootProcessAppProcess.relocateScript(relocationToken)
            } else "" to appProcess
            val userId = android.os.Process.myUid() / 100000    // PER_USER_RANGE
            val launch = RootProcessAppProcess.launchString(
                packageCodePath = packageCodePath,
                clazz = RootProcessBootstrap::class.java.name,
                appProcess = executable,
                niceName = niceName,
            )
            return buildString {
                appendLine("exec 3>$markerPath || exit 1")
                appendLine("exec 4<$stdinPath || exit 1")
                appendLine("exec 5>$stdoutPath || exit 1")
                appendLine("exec 6>$stderrPath || exit 1")
                append(relocationScript)
                appendLine("printf '%s\\n' ${ShellScript.quote(STARTUP_MARKER_STARTED)} >&3 || exit 1")
                appendLine("exec 3>&-")
                appendLine("${RootServiceHandoff.AUTHORITY_ENV}=${ShellScript.quote(handoffAuthority)} ${
                    RootServiceHandoff.TOKEN_ENV}=${ShellScript.quote(handoffToken)} ${
                    RootProcessOwnership.SOCKET_ENV}=${ShellScript.quote(ownershipSocketName)} $launch ${
                    ShellScript.quote(packageName)} $userId <&4 >&5 2>&6 4<&- 5>&- 6>&-")
            }
        }
    }
}
