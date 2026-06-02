package be.mygod.librootkotlinx.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProcessLauncherTest {
    @Test
    fun buildStartupCommandUsesDirectApkClasspathAndRedirection() {
        val command = RootProcessLauncher.buildStartupCommand(
            packageName = "pkg",
            packageCodePath = "/data/app/pkg/base.apk:/data/app/pkg/split_config.arm64_v8a.apk",
            targetUid = 1012345,
            stdioRedirect = " <${RootProcessPipes.appFdPath(4, 123)}" +
                    " >${RootProcessPipes.appFdPath(5, 123)}" +
                    " 2>${RootProcessPipes.appFdPath(6, 123)}",
            markerRedirect = RootProcessPipes.appFdPath(7, 123),
            startupNonce = "nonce",
            ownershipSocketName = "ownership",
            handoffAuthority = "authority",
            handoffToken = "token",
            appProcess = "/system/bin/app_process64",
            shouldRelocate = false,
            relocationToken = "unused",
        )

        assertEquals(
            "(if command exec 3>'/proc/123/fd/7'; then " +
                    "(if command exec <'/proc/123/fd/4' >'/proc/123/fd/5' 2>'/proc/123/fd/6'; then " +
                    "printf '%s\\n' 'librootkotlinx-started:nonce' >&3; " +
                    "exec 3>&-; " +
                    "${RootServiceHandoff.AUTHORITY_ENV}='authority' " +
                    "${RootServiceHandoff.TOKEN_ENV}='token' " +
                    "${RootProcessOwnership.SOCKET_ENV}='ownership' " +
                    "CLASSPATH='/data/app/pkg/base.apk:/data/app/pkg/split_config.arm64_v8a.apk' " +
                    "exec '/system/bin/app_process64' " +
                    "-Xnoimage-dex2oat /system/bin '--nice-name=pkg:root:10' " +
                    "${RootProcessMain::class.java.name} 'pkg' 1012345; " +
                    "else printf '%s\\n' 'librootkotlinx-failed:nonce' >&3; exit 1; fi)& " +
                    "command exec 3>&-; else exit 1; fi)",
            command,
        )
    }

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'a'\\''b'", ShellScript.quote("a'b"))
    }

    @Test
    fun appFdPathUsesAppProcessPid() {
        assertEquals("'/proc/123/fd/4'", RootProcessPipes.appFdPath(4, 123))
    }

    @Test
    fun findLinkerSectionUsesFirstMatchingDirectoryBeforeNextSection() {
        val section = RootProcessAppProcess.findLinkerSection(sequenceOf(
            "# comment",
            "dir.system = /system/bin",
            "dir.vendor = /vendor/bin",
            "[vendor]",
            "dir.late = /system/bin/app_process64",
        ), "/system/bin/app_process64")

        assertEquals("system", section)
    }

    @Test
    fun findLinkerSectionRejectsMissingMatch() {
        try {
            RootProcessAppProcess.findLinkerSection(sequenceOf("dir.vendor = /vendor/bin"), "/system/bin/app_process64")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("No valid linker section found"))
            return
        }
        throw AssertionError("Expected missing linker section to be rejected")
    }
}
