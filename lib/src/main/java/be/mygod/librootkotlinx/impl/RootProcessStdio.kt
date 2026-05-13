package be.mygod.librootkotlinx.impl

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.io.openReadChannel
import com.topjohnwu.superuser.ShellUtils
import java.io.Closeable
import java.io.IOException

internal class RootProcessHandlerStdio(
    val stdin: ParcelFileDescriptor,
    val stdout: ParcelFileDescriptor,
    val stderr: ParcelFileDescriptor,
) : Closeable {
    override fun close() {
        closeRootProcessResource(stdin)
        closeRootProcessResource(stdout)
        closeRootProcessResource(stderr)
    }
}

internal class RootProcessStdio {
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

    fun takeHandlerStdio() = RootProcessHandlerStdio(
        checkNotNull(stdinWrite),
        checkNotNull(stdoutRead),
        checkNotNull(stderrRead),
    ).also {
        stdinWrite = null
        stdoutRead = null
        stderrRead = null
    }

    fun openMarkerReadChannel() =
        checkNotNull(markerRead).openReadChannel(Handler(Looper.getMainLooper())).also { markerRead = null }

    fun closeMarkerWrite() {
        closeRootProcessResource(markerWrite)
        markerWrite = null
    }

    fun closeRemaining() {
        closeRootProcessResource(stdinRead)
        closeRootProcessResource(stdinWrite)
        closeRootProcessResource(stdoutRead)
        closeRootProcessResource(stdoutWrite)
        closeRootProcessResource(stderrRead)
        closeRootProcessResource(stderrWrite)
        closeRootProcessResource(markerRead)
        closeRootProcessResource(markerWrite)
        stdinRead = null
        stdinWrite = null
        stdoutRead = null
        stdoutWrite = null
        stderrRead = null
        stderrWrite = null
        markerRead = null
        markerWrite = null
    }

    companion object {
        internal fun appFdPath(fd: Int, pid: Int = Process.myPid()) = ShellUtils.escapedString("/proc/$pid/fd/$fd")

        private fun fdPath(descriptor: ParcelFileDescriptor) = appFdPath(descriptor.fd)
    }
}

private fun closeRootProcessResource(closeable: Closeable?) {
    if (closeable != null) try {
        closeable.close()
    } catch (e: IOException) {
        Logger.me.w("Failed to close root process resource", e)
    }
}
