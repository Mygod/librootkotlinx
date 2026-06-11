package be.mygod.librootkotlinx

import android.net.Credentials
import android.os.ParcelFileDescriptor

/**
 * App-side handles and kernel-authenticated identity for one owned root process.
 *
 * [process] is the local `su` process started by this library. Depending on the root implementation, it may be the
 * same process as the root `app_process` after an exec-in-place transition, or it may be a proxy client for a
 * privileged daemon that runs the root `app_process` elsewhere. In the proxy case, [Process.waitFor] and `awaitExit()`
 * can report a daemon/client-synthesized status instead of the root JVM's real exit status or signal. Treat that exit
 * code as best-effort diagnostic data, not as the authoritative service lifecycle signal.
 *
 * [peerCredentials] is the raw `LocalSocket.peerCredentials` snapshot from the root process ownership socket.
 */
class RootProcess internal constructor(
    /**
     * Local `su` process started by this library.
     */
    val process: Process,
    /**
     * Kernel-authenticated credentials for the process that connected to the ownership socket.
     */
    val peerCredentials: Credentials,
    /**
     * Root app_process stdin.
     */
    val stdin: ParcelFileDescriptor,
    /**
     * Root app_process stdout.
     */
    val stdout: ParcelFileDescriptor,
    /**
     * Root app_process stderr.
     */
    val stderr: ParcelFileDescriptor,
)
