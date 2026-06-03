@file:JvmName("ProcessCompat")

package be.mygod.librootkotlinx.io

import android.annotation.SuppressLint
import android.os.Build
import android.os.ParcelFileDescriptor
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
    suspend fun awaitExit(): Int = runInterruptible(Dispatchers.IO) { process.waitFor() }

    override fun close() {
        var failure: IOException? = null
        fun ParcelFileDescriptor?.closeOwned() {
            this ?: return
            try {
                close()
            } catch (e: IOException) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        try {
            stdin.closeOwned()
            stdout.closeOwned()
            stderr.closeOwned()
        } finally {
            process.destroy()
        }
        failure?.let { throw it }
    }

    companion object {
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

        internal fun fdPath(descriptor: ParcelFileDescriptor) = "/proc/${android.os.Process.myPid()}/fd/${
            descriptor.fd}"

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
    }
}
