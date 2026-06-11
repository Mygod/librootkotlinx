package be.mygod.librootkotlinx.impl

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import be.mygod.librootkotlinx.NoShellException
import be.mygod.librootkotlinx.io.ProcessPipes
import be.mygod.librootkotlinx.io.awaitExit
import be.mygod.librootkotlinx.io.openUnboundedReadChannel
import be.mygod.librootkotlinx.io.openWriteChannel
import be.mygod.librootkotlinx.io.startPipes
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
 * The command keeps the base APK as the initial bootstrap classpath entry, leaves app_process stdio attached to the
 * root shell session, and writes a marker to a dedicated marker pipe after setup succeeds and immediately before
 * app_process.
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
    suspend fun launch(marker: RootProcessStartupMarker): RootProcessPipes {
        val pipes = executeRootShell(
            buildStartupCommand(
                packageName = packageName,
                packageCodePath = packageCodePath,
                niceName = niceName,
                markerPath = marker.markerPath,
                ownershipSocketName = ownershipSocketName,
                handoffAuthority = handoffAuthority,
                handoffToken = handoffToken,
                appProcess = AppProcess.myExe,
                shouldRelocate = Build.VERSION.SDK_INT < 26,
                relocationToken = if (Build.VERSION.SDK_INT < 26) relocationToken() else "",
            ),
        )
        try {
            val channel = marker.openMarkerReadChannel()
            try {
                val line = coroutineScope {
                    val markerLine = async { channel.readLine() }
                    val exited = async { pipes.process.awaitExit() }
                    try {
                        select {
                            markerLine.onAwait { it }
                            exited.onAwait { code ->
                                // The shell can write no more after exiting, so closing the last marker write end
                                // settles the marker read deterministically.
                                marker.closeMarkerWrite()
                                markerLine.await() ?: throw NoShellException(
                                    "Root shell exited unexpectedly with code $code${pipes.diagnosticsSuffix()}")
                            }
                        }
                    } finally {
                        markerLine.cancelAndJoin()
                        exited.cancelAndJoin()
                    }
                }
                when (line) {
                    STARTUP_MARKER_STARTED -> { }
                    null -> throw NoShellException("Root shell marker pipe closed${pipes.diagnosticsSuffix()}")
                    else -> throw NoShellException(
                        "Unexpected root shell startup marker: $line${pipes.diagnosticsSuffix()}")
                }
            } catch (e: IOException) {
                throw NoShellException("Root service startup marker read failed", e)
            } finally {
                marker.closeMarkerWrite()
                channel.cancel(null)
            }
            return pipes
        } catch (e: Throwable) {
            pipes.close()
            throw e
        }
    }

    private suspend fun executeRootShell(command: String): RootProcessPipes {
        val process = startRootShell()
        val pipes = try {
            val handler = Handler(Looper.getMainLooper())
            RootProcessPipes(
                process.process,
                process.requireStdin().openWriteChannel(handler),
                process.requireStdout().openUnboundedReadChannel(handler),
                process.requireStderr().openUnboundedReadChannel(handler),
            )
        } catch (e: Throwable) {
            process.close()
            throw e
        }
        try {
            // Leave stdin open after the command: the marker confirms delivery, and the root app_process inherits it.
            pipes.stdin.writeFully(command.encodeToByteArray())
            pipes.stdin.flush()
        } catch (e: Throwable) {
            pipes.close()
            throw when (e) {
                is IOException, is ErrnoException -> NoShellException("Root shell died before root service startup", e)
                else -> e
            }
        }
        return pipes
    }

    private fun startRootShell(): ProcessPipes {
        var failure: IOException? = null
        for (command in SU_COMMANDS) try {
            return ProcessBuilder(command).startPipes()
        } catch (e: IOException) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        throw NoShellException("Root shell is not available", failure!!)
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

        fun buildStartupCommand(
            packageName: String,
            packageCodePath: String,
            niceName: String,
            markerPath: String,
            ownershipSocketName: String,
            handoffAuthority: String,
            handoffToken: String,
            appProcess: String,
            shouldRelocate: Boolean,
            relocationToken: String,
        ): String {
            val (relocationScript, executable) = if (shouldRelocate) {
                AppProcess.relocateScript(relocationToken)
            } else "" to appProcess
            val userId = android.os.Process.myUid() / 100000    // PER_USER_RANGE
            val launch = AppProcess.launchString(
                packageCodePath = packageCodePath,
                clazz = RootProcessBootstrap::class.java.name,
                appProcess = executable,
                niceName = niceName,
            )
            val phhLaunch = AppProcess.launchString(
                packageCodePath = packageCodePath,
                clazz = RootProcessBootstrap::class.java.name,
                appProcess = "runcon u:r:su:s0 ${AppProcess.quote(executable)}",
                niceName = niceName,
            )
            val args = " $packageName $userId $ownershipSocketName $handoffAuthority $handoffToken"
            return buildString {
                appendLine("exec 3>$markerPath || exit 1")
                append(relocationScript)
                // PHH Superuser starts commands in phhsu_daemon, which blocks app-to-root Binder; see:
                // https://github.com/Mygod/VPNHotspot/issues/753
                appendLine("if [ \"$(id -Z 2>/dev/null)\" = \"u:r:phhsu_daemon:s0\" ] && runcon u:r:su:s0 true 2>/dev/null; then")
                appendLine("  phh_runcon=1")
                appendLine("fi")
                appendLine("printf '%s\\n' $STARTUP_MARKER_STARTED >&3 || exit 1")
                appendLine("exec 3>&-")
                appendLine($$"if [ \"$phh_runcon\" = 1 ]; then")
                appendLine("  $phhLaunch$args")
                appendLine("else")
                appendLine("  $launch$args")
                appendLine("fi")
            }
        }
    }
}
