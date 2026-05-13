package be.mygod.librootkotlinx.impl.libsu

import be.mygod.librootkotlinx.impl.RootProcessOwnership
import be.mygod.librootkotlinx.impl.RootProcessStdio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RootProcessLauncherTest {
    @Test
    fun rewriteCommandSwapsMainClassClasspathAndRedirection() {
        val command = "(LIBSU_VERBOSE=1 CLASSPATH=/data/user_de/0/pkg/cache/main.jar " +
                "/system/bin/app_process64 -Xnoimage-dex2oat /system/bin --nice-name=pkg:root:0 " +
                "com.topjohnwu.superuser.internal.RootServerMain 'pkg/.RootService' 1000 start " +
                ">/dev/null 2>&1)&"
        val rewritten = RootProcessLauncher.rewriteCommand(
            command,
            "/data/app/pkg/base.apk",
            " <${RootProcessStdio.appFdPath(4, 123)}" +
                    " >${RootProcessStdio.appFdPath(5, 123)}" +
                    " 2>${RootProcessStdio.appFdPath(6, 123)}",
            RootProcessStdio.appFdPath(7, 123),
            "nonce",
            "ownership",
        )

        assertEquals(
            "(if command exec 3>'/proc/123/fd/7'; then " +
                    "(if command exec <'/proc/123/fd/4' >'/proc/123/fd/5' 2>'/proc/123/fd/6'; then " +
                    "printf '%s\\n' 'librootkotlinx-started:nonce' >&3; " +
                    "exec 3>&-; " +
                    "${RootProcessOwnership.SOCKET_ENV}='ownership' LIBSU_VERBOSE=1 " +
                    "CLASSPATH='/data/user_de/0/pkg/cache/main.jar:/data/app/pkg/base.apk' " +
                    "exec /system/bin/app_process64 -Xnoimage-dex2oat /system/bin --nice-name=pkg:root:0 " +
                    "${RootProcessMain::class.java.name} 'pkg/.RootService' 1000 start; " +
                    "else printf '%s\\n' 'librootkotlinx-failed:nonce' >&3; exit 1; fi)& " +
                    "command exec 3>&-; else exit 1; fi)",
            rewritten,
        )
    }

    @Test
    fun rewriteCommandRejectsUnexpectedLibsuShape() {
        try {
            RootProcessLauncher.rewriteCommand(
                "CLASSPATH=/cache/main.jar com.topjohnwu.superuser.internal.RootServerMain",
                "/data/app/pkg/base.apk",
                " >/dev/null 2>&1",
                " >/tmp/marker",
                "nonce",
                "ownership",
            )
            fail("Expected malformed libsu command to be rejected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Unexpected libsu RootService startup command"))
        }
    }

    @Test
    fun appFdPathUsesAppProcessPid() {
        assertEquals("'/proc/123/fd/4'", RootProcessStdio.appFdPath(4, 123))
    }
}
