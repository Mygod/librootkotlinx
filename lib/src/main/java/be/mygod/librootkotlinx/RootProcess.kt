package be.mygod.librootkotlinx

import android.net.Credentials
import be.mygod.librootkotlinx.io.FileDescriptorByteReadChannel
import io.ktor.utils.io.ByteWriteChannel

/**
 * App-side handles and kernel-authenticated identity for one owned root process.
 *
 * The stdio channels own their pipe descriptors: cancelling a channel closes its descriptor, and all three channels
 * are closed automatically after the root lifecycle handler completes.
 */
class RootProcess internal constructor(
    /**
     * Local `su` process started by this library. Depending on the root implementation, it may be the same process as
     * the root `app_process` after an exec-in-place transition, or it may be a proxy client for a privileged daemon
     * that runs the root `app_process` elsewhere. In the proxy case, [Process.waitFor] and `awaitExit()` can report a
     * daemon/client-synthesized status instead of the root JVM's real exit status or signal. Treat that exit code as
     * best-effort diagnostic data, not as the authoritative service lifecycle signal.
     */
    val process: Process,
    /**
     * Kernel-authenticated credentials for the process that connected to the ownership socket.
     * This may or may not be the same [process] depending on su implementations.
     */
    val peerCredentials: Credentials,
    /**
     * Write channel for root app_process stdin inherited from the root shell session. It is recommended to keep this
     * open since the overhead is small and system root daemon might use this as a signal for liveliness.
     */
    val stdin: ByteWriteChannel,
    /**
     * Read channel for root app_process stdout inherited from the root shell session, carrying all output since the
     * root shell was started. Treat this as diagnostics output; use [RootServer] APIs or your own IPC channel for
     * structured data.
     */
    val stdout: FileDescriptorByteReadChannel,
    /**
     * Read channel for root app_process stderr inherited from the root shell session, carrying all output since the
     * root shell was started. Treat this as diagnostics output; use [RootServer] APIs or your own IPC channel for
     * structured data.
     */
    val stderr: FileDescriptorByteReadChannel,
)
