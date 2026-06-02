package be.mygod.librootkotlinx.impl

import be.mygod.librootkotlinx.NoShellException
import io.ktor.utils.io.readLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/**
 * Builds and executes the short-lived root shell command that starts the detached root app_process.
 *
 * This owns libsu RootServiceManager's app_process command contract instead of rewriting libsu's generated command.
 * The command keeps the current app APK as the only classpath entry, supplies the app native library directory before
 * app_process creates its class loader, redirects the detached child stdio to app-owned pipes, and uses a marker pipe
 * only to confirm that the child inherited those descriptors before the shell exits.
 *
 * libsu source:
 * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L191-L233
 */
internal class RootProcessLauncher(
    private val packageName: String,
    private val packageCodePath: String,
    private val packageNativeLibrarySearchPath: String?,
    private val codeCacheDir: () -> File,
    private val ownershipSocketName: String,
    private val handoffAuthority: String,
    private val handoffToken: String,
) {
    suspend fun launch(pipes: RootProcessPipes) {
        val startupNonce = UUID.randomUUID().toString()
        runInterruptible(Dispatchers.IO) {
            executeRootShell(buildStartupCommand(
                packageName = packageName,
                packageCodePath = packageCodePath,
                packageNativeLibrarySearchPath = packageNativeLibrarySearchPath,
                stdioRedirect = pipes.redirect,
                markerRedirect = pipes.markerRedirect,
                startupNonce = startupNonce,
                ownershipSocketName = ownershipSocketName,
                handoffAuthority = handoffAuthority,
                handoffToken = handoffToken,
                appProcess = RootProcessAppProcess.myExe,
                shouldRelocate = RootProcessAppProcess.shouldRelocateHeuristics,
                relocationToken = relocationToken(),
            ))
        }
        pipes.closeMarkerWrite()
        val channel = pipes.openMarkerReadChannel()
        try {
            while (true) {
                val marker = channel.readLine()
                if (marker == startupMarker(STARTUP_MARKER_STARTED, startupNonce)) break
                if (marker == startupMarker(STARTUP_MARKER_FAILED, startupNonce)) {
                    throw IOException("Root service stdio redirection failed")
                }
                if (marker == null) throw IOException("Root service startup marker pipe closed")
            }
        } finally {
            channel.cancel(null)
        }
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

    private fun executeRootShell(command: String) {
        val process = startRootShell()
        val output = StringBuilder()
        try {
            val writer = process.outputStream.bufferedWriter()
            val reader = process.inputStream.bufferedReader()
            val shellNonce = UUID.randomUUID().toString()
            try {
                writer.write("echo ${ShellScript.quote(shellNonce)}")
                writer.newLine()
                writer.flush()
            } catch (e: IOException) {
                throw NoShellException("Root shell died before startup", e)
            }
            while (true) {
                val line = reader.readLine() ?: throw NoShellException(
                    "Root shell exited before startup${process.waitFor().exitSuffix(output)}",
                )
                if (line.endsWith(shellNonce)) {
                    if (line.length > shellNonce.length) output.appendLine(line.dropLast(shellNonce.length))
                    break
                }
                output.appendLine(line)
            }
            try {
                writer.write(command)
                writer.newLine()
                writer.write("exit")
                writer.newLine()
                writer.close()
            } catch (e: IOException) {
                throw NoShellException("Root shell died before root service startup", e)
            }
            while (true) output.appendLine(reader.readLine() ?: break)
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IOException("Root service startup command failed${exitCode.exitSuffix(output)}")
            }
        } catch (e: InterruptedException) {
            process.destroy()
            Thread.currentThread().interrupt()
            throw e
        }
    }

    private fun startRootShell(): Process {
        var failure: IOException? = null
        for (command in SU_COMMANDS) try {
            return ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        throw NoShellException("Root shell is not available", checkNotNull(failure))
    }

    internal companion object {
        private val SU_COMMANDS = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "su",
        )
        private const val PER_USER_RANGE = 100000
        private const val STARTUP_MARKER_STARTED = "librootkotlinx-started:"
        private const val STARTUP_MARKER_FAILED = "librootkotlinx-failed:"

        internal fun buildStartupCommand(
            packageName: String,
            packageCodePath: String,
            packageNativeLibrarySearchPath: String? = null,
            targetUid: Int = android.os.Process.myUid(),
            stdioRedirect: String,
            markerRedirect: String,
            startupNonce: String,
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
            val launch = RootProcessAppProcess.launchString(
                packageCodePath = packageCodePath,
                packageNativeLibrarySearchPath = packageNativeLibrarySearchPath,
                clazz = RootProcessMain::class.java.name,
                appProcess = executable,
                niceName = "$packageName:root:${targetUid / PER_USER_RANGE}",
            )
            val env = "${RootServiceHandoff.AUTHORITY_ENV}=${ShellScript.quote(handoffAuthority)} " +
                    "${RootServiceHandoff.TOKEN_ENV}=${ShellScript.quote(handoffToken)} " +
                    "${RootProcessOwnership.SOCKET_ENV}=${ShellScript.quote(ownershipSocketName)}"
            val success = ShellScript.quote(startupMarker(STARTUP_MARKER_STARTED, startupNonce))
            val failed = ShellScript.quote(startupMarker(STARTUP_MARKER_FAILED, startupNonce))
            return "(if command exec 3>$markerRedirect; then " +
                    "(if command exec$stdioRedirect; then " +
                    "printf '%s\\n' $success >&3; " +
                    "exec 3>&-; " +
                    relocationScript +
                    "$env $launch ${ShellScript.quote(packageName)} $targetUid; " +
                    "else printf '%s\\n' $failed >&3; exit 1; fi)& " +
                    "command exec 3>&-; else exit 1; fi)"
        }

        private fun startupMarker(prefix: String, nonce: String) = prefix + nonce

        private fun Int.exitSuffix(output: StringBuilder): String {
            val trimmed = output.toString().trim()
            return if (trimmed.isEmpty()) " with exit code $this" else " with exit code $this: $trimmed"
        }
    }
}
