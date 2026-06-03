package be.mygod.librootkotlinx.impl

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.io.ProcessPipes
import be.mygod.librootkotlinx.io.openReadChannel
import java.io.Closeable
import java.io.IOException

internal class RootProcessHandlerStdio(
    val stdin: ParcelFileDescriptor,
    val stdout: ParcelFileDescriptor,
    val stderr: ParcelFileDescriptor,
) : Closeable {
    override fun close() {
        closeRootProcessPipe(stdin)
        closeRootProcessPipe(stdout)
        closeRootProcessPipe(stderr)
    }
}

internal class RootProcessPipes {
    private var stdinRead: ParcelFileDescriptor?
    private var stdinWrite: ParcelFileDescriptor?
    private var stdoutRead: ParcelFileDescriptor?
    private var stdoutWrite: ParcelFileDescriptor?
    private var stderrRead: ParcelFileDescriptor?
    private var stderrWrite: ParcelFileDescriptor?
    private var markerRead: ParcelFileDescriptor?
    private var markerWrite: ParcelFileDescriptor?

    val stdinPath get() = ProcessPipes.fdPath(checkNotNull(stdinRead))
    val stdoutPath get() = ProcessPipes.fdPath(checkNotNull(stdoutWrite))
    val stderrPath get() = ProcessPipes.fdPath(checkNotNull(stderrWrite))
    val markerPath get() = ProcessPipes.fdPath(checkNotNull(markerWrite))

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
        closeRootProcessPipe(markerWrite)
        markerWrite = null
    }

    fun closeRemaining() {
        closeRootProcessPipe(stdinRead)
        closeRootProcessPipe(stdinWrite)
        closeRootProcessPipe(stdoutRead)
        closeRootProcessPipe(stdoutWrite)
        closeRootProcessPipe(stderrRead)
        closeRootProcessPipe(stderrWrite)
        closeRootProcessPipe(markerRead)
        closeRootProcessPipe(markerWrite)
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

private fun closeRootProcessPipe(closeable: Closeable?) {
    if (closeable != null) try {
        closeable.close()
    } catch (e: IOException) {
        Logger.me.w("Failed to close root process pipe", e)
    }
}
