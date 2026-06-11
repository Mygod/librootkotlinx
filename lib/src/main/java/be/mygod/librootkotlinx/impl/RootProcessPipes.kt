package be.mygod.librootkotlinx.impl

import android.system.ErrnoException
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.io.FileDescriptorByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.readByteArray
import kotlinx.io.Buffer
import kotlinx.io.readString
import java.io.IOException

/**
 * Owns the app-side endpoints of one root shell process: the process handle and its stdio channels.
 *
 * Each stdio descriptor is owned by exactly one channel for the whole process lifetime. Nothing consumes stdout/stderr
 * during startup, so all root shell session output stays buffered in the channels for either failure diagnostics or
 * the lifecycle consumer.
 */
internal class RootProcessPipes(
    val process: Process,
    val stdin: ByteWriteChannel,
    val stdout: FileDescriptorByteReadChannel,
    val stderr: FileDescriptorByteReadChannel,
) {
    /**
     * Consumes buffered stdout/stderr into a failure message suffix, or "" if there was no output.
     *
     * This never waits for more output: once [process] has exited, the drain reads descriptor EOF synchronously and
     * captures everything; for a still-live writer this is a best-effort snapshot.
     */
    suspend fun diagnosticsSuffix(): String {
        val buffer = Buffer()
        stdout.snapshotTo(buffer)
        stderr.snapshotTo(buffer)
        val diagnostics = buffer.readString().trim()
        return if (diagnostics.isEmpty()) "" else ": $diagnostics"
    }

    private suspend fun FileDescriptorByteReadChannel.snapshotTo(buffer: Buffer) {
        // Empty the channel first so that drain cannot stall on channel backpressure.
        while (availableForRead > 0) buffer.write(readByteArray(availableForRead))
        drain()
        while (availableForRead > 0) buffer.write(readByteArray(availableForRead))
    }

    fun closeStdio() {
        fun closeEndpoint(name: String, close: () -> Unit) {
            try {
                close()
            } catch (e: IOException) {
                Logger.me.d("Failed to close root process $name", e)
            } catch (e: ErrnoException) {
                Logger.me.d("Failed to close root process $name", e)
            }
        }
        closeEndpoint("stdin") { stdin.cancel(null) }
        closeEndpoint("stdout") { stdout.cancel(null) }
        closeEndpoint("stderr") { stderr.cancel(null) }
    }

    fun close() = try {
        closeStdio()
    } finally {
        process.destroy()
    }
}
