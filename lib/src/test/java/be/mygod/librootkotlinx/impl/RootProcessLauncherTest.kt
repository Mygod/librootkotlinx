package be.mygod.librootkotlinx.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProcessLauncherTest {
    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'a'\\''b'", ShellScript.quote("a'b"))
    }

    @Test
    fun relocationScriptCopiesAppProcessToDev() {
        val (script, relocated) = RootProcessAppProcess.relocateScript("token")

        assertEquals("/dev/app_process_token", relocated)
        assertEquals(
            "[ -f '/dev/app_process_token' ] || { cp ${RootProcessAppProcess.myExe} '/dev/app_process_token' && " +
                    "chmod 700 '/dev/app_process_token'; } || exit 1\n",
            script.toString(),
        )
    }

    @Test
    fun startupCommandUsesPlainMultilineShellAndSuccessMarker() {
        val command = RootProcessLauncher.buildStartupCommand(
            packageName = "be.mygod.demo",
            packageCodePath = "/data/app/base.apk",
            niceName = "be.mygod.demo:librootkotlinx:0",
            stdinPath = "'/proc/123/fd/4'",
            stdoutPath = "'/proc/123/fd/5'",
            stderrPath = "'/proc/123/fd/6'",
            markerPath = "'/proc/123/fd/7'",
            ownershipSocketName = "socket",
            handoffAuthority = "authority",
            handoffToken = "token",
            appProcess = "/system/bin/app_process",
            shouldRelocate = false,
            relocationToken = "",
        )

        assertEquals(
            """
            exec 3>'/proc/123/fd/7' || exit 1
            exec 4<'/proc/123/fd/4' || exit 1
            exec 5>'/proc/123/fd/5' || exit 1
            exec 6>'/proc/123/fd/6' || exit 1
            printf '%s\n' 'librootkotlinx-started' >&3 || exit 1
            exec 3>&-
            LIBROOTKOTLINX_HANDOFF_AUTHORITY='authority' LIBROOTKOTLINX_HANDOFF_TOKEN='token' LIBROOTKOTLINX_OWNERSHIP_SOCKET='socket' CLASSPATH='/data/app/base.apk' exec '/system/bin/app_process' -Xnoimage-dex2oat /system/bin '--nice-name=be.mygod.demo:librootkotlinx:0' be.mygod.librootkotlinx.impl.RootProcessBootstrap 'be.mygod.demo' 0 <&4 >&5 2>&6 4<&- 5>&- 6>&-
            """.trimIndent() + "\n",
            command,
        )
        assertTrue(command.indexOf("printf '%s\\n' 'librootkotlinx-started'") < command.indexOf("exec '/system/bin/app_process'"))
        assertFalse(command.contains("command exec"))
        assertFalse(command.contains("librootkotlinx-failed"))
        assertFalse(command.contains(") &"))
        assertFalse(command.contains("\nexit\n"))
    }

    @Test
    fun startupCommandMarksStartedAfterRelocationSetup() {
        val command = RootProcessLauncher.buildStartupCommand(
            packageName = "be.mygod.demo",
            packageCodePath = "/data/app/base.apk",
            niceName = "be.mygod.demo:librootkotlinx:0",
            stdinPath = "'/proc/123/fd/4'",
            stdoutPath = "'/proc/123/fd/5'",
            stderrPath = "'/proc/123/fd/6'",
            markerPath = "'/proc/123/fd/7'",
            ownershipSocketName = "socket",
            handoffAuthority = "authority",
            handoffToken = "token",
            appProcess = "/system/bin/app_process",
            shouldRelocate = true,
            relocationToken = "token",
        )

        val relocation = command.indexOf("[ -f '/dev/app_process_token' ]")
        val marker = command.indexOf("printf '%s\\n' 'librootkotlinx-started'")
        val launch = command.indexOf("exec '/dev/app_process_token'")
        assertTrue(relocation >= 0)
        assertTrue(relocation < marker)
        assertTrue(marker < launch)
    }
}
