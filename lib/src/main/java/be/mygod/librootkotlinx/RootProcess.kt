package be.mygod.librootkotlinx

import android.net.Credentials
import android.os.ParcelFileDescriptor

/**
 * App-side handles and kernel-authenticated identity for one owned root process.
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
     * Root app_process stdin inherited from the root shell session. It is recommended to keep this open since the
     * overhead is small and system root daemon might use this as a signal for liveliness.
     */
    val stdin: ParcelFileDescriptor,
    /**
     * Root app_process stdout inherited from the root shell session. Treat this as diagnostics output; use [RootServer]
     * APIs or your own IPC channel for structured data.
     */
    val stdout: ParcelFileDescriptor,
    /**
     * Root app_process stderr inherited from the root shell session. Treat this as diagnostics output; use [RootServer]
     * APIs or your own IPC channel for structured data.
     */
    val stderr: ParcelFileDescriptor,
)
