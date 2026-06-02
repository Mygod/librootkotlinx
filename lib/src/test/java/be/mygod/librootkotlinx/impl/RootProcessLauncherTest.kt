package be.mygod.librootkotlinx.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class RootProcessLauncherTest {
    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'a'\\''b'", ShellScript.quote("a'b"))
    }

    @Test
    fun appFdPathUsesAppProcessPid() {
        assertEquals("'/proc/123/fd/4'", RootProcessPipes.appFdPath(4, 123))
    }

    @Test
    fun relocationScriptCopiesAppProcessToDev() {
        val (script, relocated) = RootProcessAppProcess.relocateScript("token")

        assertEquals("/dev/app_process_token", relocated)
        assertEquals(
            "[ -f '/dev/app_process_token' ] || { mkdir -p '/dev' && " +
                    "cp ${RootProcessAppProcess.myExe} '/dev/app_process_token' && " +
                    "chmod 700 '/dev/app_process_token'; } || exit 1\n",
            script.toString(),
        )
    }
}
