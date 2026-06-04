package be.mygod.librootkotlinx.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProcessLauncherTest {
    @Test
    fun startupCommandUsesPhhRunconOnlyForExactPhhsuContext() {
        val userId = android.os.Process.myUid() / 100000    // PER_USER_RANGE

        assertEquals(
            """
            exec 3>/proc/self/fd/3 || exit 1
            exec 4</proc/self/fd/4 || exit 1
            exec 5>/proc/self/fd/5 || exit 1
            exec 6>/proc/self/fd/6 || exit 1
            if [ "${'$'}(id -Z 2>/dev/null)" = "u:r:phhsu_daemon:s0" ] && runcon u:r:su:s0 true 2>/dev/null; then
              phh_runcon=1
            fi
            printf '%s\n' librootkotlinx-started >&3 || exit 1
            exec 3>&-
            if [ "${'$'}phh_runcon" = 1 ]; then
              CLASSPATH='/data/app/example/base.apk' exec runcon u:r:su:s0 '/system/bin/app_process' -Xnoimage-dex2oat /system/bin '--nice-name=example:root' be.mygod.librootkotlinx.impl.RootProcessBootstrap be.example $userId ownership authority token <&4 >&5 2>&6 4<&- 5>&- 6>&-
            else
              CLASSPATH='/data/app/example/base.apk' exec /system/bin/app_process -Xnoimage-dex2oat /system/bin '--nice-name=example:root' be.mygod.librootkotlinx.impl.RootProcessBootstrap be.example $userId ownership authority token <&4 >&5 2>&6 4<&- 5>&- 6>&-
            fi
            """.trimIndent() + "\n",
            RootProcessLauncher.buildStartupCommand(
                packageName = "be.example",
                packageCodePath = "/data/app/example/base.apk",
                niceName = "example:root",
                appProcessVmOption = null,
                stdinPath = "/proc/self/fd/4",
                stdoutPath = "/proc/self/fd/5",
                stderrPath = "/proc/self/fd/6",
                markerPath = "/proc/self/fd/3",
                ownershipSocketName = "ownership",
                handoffAuthority = "authority",
                handoffToken = "token",
                appProcess = "/system/bin/app_process",
                shouldRelocate = false,
                relocationToken = "",
            ),
        )
    }

    @Test
    fun startupCommandAppliesPhhProbeAfterAppProcessRelocation() {
        val command = RootProcessLauncher.buildStartupCommand(
            packageName = "be.example",
            packageCodePath = "/data/app/example/base.apk",
            niceName = "example:root",
            appProcessVmOption = null,
            stdinPath = "/proc/self/fd/4",
            stdoutPath = "/proc/self/fd/5",
            stderrPath = "/proc/self/fd/6",
            markerPath = "/proc/self/fd/3",
            ownershipSocketName = "ownership",
            handoffAuthority = "authority",
            handoffToken = "token",
            appProcess = "/system/bin/app_process",
            shouldRelocate = true,
            relocationToken = "token",
        )

        assertTrue(command.indexOf("[ -f /dev/app_process_token ]") < command.indexOf(
            "exec runcon u:r:su:s0 '/dev/app_process_token'",
        ))
        assertTrue(command.contains("exec /dev/app_process_token -Xnoimage-dex2oat"))
    }
}
