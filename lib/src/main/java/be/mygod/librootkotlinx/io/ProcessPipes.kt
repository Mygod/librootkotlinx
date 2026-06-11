@file:JvmName("ProcessCompat")

package be.mygod.librootkotlinx.io

import android.annotation.SuppressLint
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import be.mygod.librootkotlinx.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException

/**
 * A started process with stdin/stdout/stderr exposed as [ParcelFileDescriptor] pipes.
 *
 * A null descriptor means that stream was not requested by [startPipes].
 */
class ProcessPipes internal constructor(
    val process: Process,
    val stdin: ParcelFileDescriptor?,
    val stdout: ParcelFileDescriptor?,
    val stderr: ParcelFileDescriptor?,
) : Closeable {
    /**
     * Returns [stdin], or throws if this process was started without a stdin pipe.
     */
    fun requireStdin(): ParcelFileDescriptor = checkNotNull(stdin) { "stdin pipe was not requested" }

    /**
     * Returns [stdout], or throws if this process was started without a stdout pipe.
     */
    fun requireStdout(): ParcelFileDescriptor = checkNotNull(stdout) { "stdout pipe was not requested" }

    /**
     * Returns [stderr], or throws if this process was started without a stderr pipe.
     */
    fun requireStderr(): ParcelFileDescriptor = checkNotNull(stderr) { "stderr pipe was not requested" }

    override fun close() {
        try {
            closeStdio()
        } finally {
            process.destroy()
        }
    }
    internal fun closeStdio() {
        var failure: IOException? = null
        fun ParcelFileDescriptor?.closeOwned() {
            this ?: return
            try {
                close()
            } catch (e: IOException) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        stdin.closeOwned()
        stdout.closeOwned()
        stderr.closeOwned()
        failure?.let { throw it }
    }
}

/**
 * Starts this process with stdin/stdout/stderr connected to [ParcelFileDescriptor] pipes.
 */
@Throws(IOException::class)
fun ProcessBuilder.startPipes(
    stdin: Boolean = true,
    stdout: Boolean = true,
    stderr: Boolean = true,
) = if (Build.VERSION.SDK_INT >= 24) {
    var stdinPipe: Array<ParcelFileDescriptor>? = null
    var stdoutPipe: Array<ParcelFileDescriptor>? = null
    var stderrPipe: Array<ParcelFileDescriptor>? = null
    var process: Process? = null
    try {
        // AOSP libcore has these redirect methods from android-7.0.0_r1, but SDK metadata marks them API 26.
        if (stdin) {
            stdinPipe = ParcelFileDescriptor.createPipe()
            @SuppressLint("NewApi") redirectInput(File(fdPath(stdinPipe[0])))
        }
        if (stdout) {
            stdoutPipe = ParcelFileDescriptor.createPipe()
            @SuppressLint("NewApi") redirectOutput(File(fdPath(stdoutPipe[1])))
        }
        if (stderr) {
            stderrPipe = ParcelFileDescriptor.createPipe()
            @SuppressLint("NewApi") redirectError(File(fdPath(stderrPipe[1])))
        }
        process = start()
        stdinPipe?.get(0)?.close()
        stdoutPipe?.get(1)?.close()
        stderrPipe?.get(1)?.close()
        ProcessPipes(process, stdinPipe?.get(1), stdoutPipe?.get(0), stderrPipe?.get(0))
    } catch (e: Throwable) {
        process?.destroy()
        stdinPipe?.forEach { it.close(e) }
        stdoutPipe?.forEach { it.close(e) }
        stderrPipe?.forEach { it.close(e) }
        throw e
    }
} else {
    val process = start()
    var pfdin: ParcelFileDescriptor? = null
    var pfdout: ParcelFileDescriptor? = null
    var pfderr: ParcelFileDescriptor? = null
    try {
        if (stdin) {
            pfdin = ParcelFileDescriptor.dup(process.outputStream.fileDescriptor())
            process.outputStream.close()
        }
        if (stdout) {
            pfdout = ParcelFileDescriptor.dup(process.inputStream.fileDescriptor())
            process.inputStream.close()
        }
        if (stderr) {
            pfderr = ParcelFileDescriptor.dup(process.errorStream.fileDescriptor())
            process.errorStream.close()
        }
        ProcessPipes(process, pfdin, pfdout, pfderr)
    } catch (e: Throwable) {
        process.destroy()
        pfdin?.close(e)
        pfdout?.close(e)
        pfderr?.close(e)
        throw e
    }
}

internal fun fdPath(descriptor: ParcelFileDescriptor) = "/proc/${android.os.Process.myPid()}/fd/${descriptor.fd}"

private val processInputStreamFd by lazy {
    Class.forName("java.lang.ProcessManager\$ProcessInputStream").getDeclaredField("fd")
        .apply { isAccessible = true }
}
private val processOutputStreamFd by lazy {
    Class.forName("java.lang.ProcessManager\$ProcessOutputStream").getDeclaredField("fd")
        .apply { isAccessible = true }
}
@Throws(IOException::class)
private fun InputStream.fileDescriptor(): FileDescriptor = when (this) {
    is FileInputStream -> fd
    else -> processInputStreamFd[this] as FileDescriptor
}
@Throws(IOException::class)
private fun OutputStream.fileDescriptor(): FileDescriptor = when (this) {
    is FileOutputStream -> fd
    else -> processOutputStreamFd[this] as FileDescriptor
}

private fun ParcelFileDescriptor.close(cause: Throwable) = try {
    close()
} catch (e: IOException) {
    cause.addSuppressed(e)
}

private val unixProcessPid by lazy {
    Class.forName(if (Build.VERSION.SDK_INT >= 24) "java.lang.UNIXProcess" else {
        "java.lang.ProcessManager\$ProcessImpl"
    }).getDeclaredField("pid").apply { isAccessible = true }
}
val Process.pid get() = unixProcessPid.getInt(this)

private val openPidFd by lazy {
    @SuppressLint("BlockedPrivateApi")
    android.os.Process::class.java.getDeclaredMethod("openPidFd", Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType)
}
@RequiresApi(31)
private suspend fun awaitPid(pid: Int) {
    val fd = try {
        openPidFd(null, pid, 0) as FileDescriptor?
    } catch (e: InvocationTargetException) {
        if ((e.targetException.cause as? ErrnoException)?.errno == OsConstants.ESRCH) return
        throw e.targetException
    } ?: throw UnsupportedOperationException()
    try {
        val awaiter = FileDescriptorEventAwaiter(fd, Looper.getMainLooper().queue)
        try {
            awaiter.await(input = true)
        } finally {
            awaiter.close()
        }
    } finally {
        try {
            Os.close(fd)
        } catch (e: ErrnoException) {
            Logger.me.w("Failed to close pidfd", e)
        }
    }
}
/**
 * Using the nonblocking variant of this API requires API 31+ AND private API bypassing (which is automatic if run as root).
 * Otherwise, it automatically falls back to [waitFor] in [Dispatchers.IO].
 */
suspend fun Process.awaitExit(): Int {
    try {
        return exitValue()
    } catch (_: IllegalThreadStateException) { }
    if (Build.VERSION.SDK_INT >= 31) try {
        awaitPid(pid)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.me.i("Failed to nonblocking awaitExit", e)
    }
    try {
        return exitValue()
    } catch (_: IllegalThreadStateException) { }
    return runInterruptible(Dispatchers.IO) { waitFor() }   // blocking last resort
}
