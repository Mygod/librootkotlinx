package be.mygod.librootkotlinx.impl

import be.mygod.librootkotlinx.NoShellException
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RootProcessLauncherTest {
    @Test
    fun startupCommandUsesPhhRunconOnlyForExactPhhsuContext() {
        val userId = android.os.Process.myUid() / 100000    // PER_USER_RANGE
        val values = arrayOf(
            DEBUG_PROPERTY_NAME,
            "kotlinx.coroutines.stacktrace.recovery",
            "kotlinx.coroutines.debug.enable.creation.stack.trace",
        )
        val previous = values.associateWith(System::getProperty)

        try {
            for (name in values) System.clearProperty(name)
            assertEquals(
                """
                command exec 3>/proc/self/fd/3 || { printf '%s\n' 'librootkotlinx: startup marker open failed' >&2; exit 100; }
                if [ "${'$'}(id -Z 2>/dev/null)" = "u:r:phhsu_daemon:s0" ] && runcon u:r:su:s0 true 2>/dev/null; then
                  phh_runcon=1
                fi
                printf '%s:%s\n' librootkotlinx-started "${'$'}${'$'}" >&3 || { printf '%s\n' 'librootkotlinx: startup marker write failed' >&2; exit 104; }
                exec 3>&-
                if [ "${'$'}phh_runcon" = 1 ]; then
                  CLASSPATH='/data/app/example/base.apk' exec runcon u:r:su:s0 '/system/bin/app_process' -Xnoimage-dex2oat /system/bin '--nice-name=example:root' be.mygod.librootkotlinx.impl.RootProcessBootstrap be.example $userId ownership authority token
                else
                  CLASSPATH='/data/app/example/base.apk' exec /system/bin/app_process -Xnoimage-dex2oat /system/bin '--nice-name=example:root' be.mygod.librootkotlinx.impl.RootProcessBootstrap be.example $userId ownership authority token
                fi
                """.trimIndent() + "\n",
                RootProcessLauncher.buildStartupCommand(
                    packageName = "be.example",
                    packageCodePath = "/data/app/example/base.apk",
                    niceName = "example:root",
                    markerPath = "/proc/self/fd/3",
                    ownershipSocketName = "ownership",
                    handoffAuthority = "authority",
                    handoffToken = "token",
                    appProcess = "/system/bin/app_process",
                    shouldRelocate = false,
                    relocationToken = "",
                ),
            )
        } finally {
            for ((name, value) in previous) {
                if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            }
        }
    }

    @Test
    fun startupCommandAppliesPhhProbeAfterAppProcessRelocation() {
        val command = RootProcessLauncher.buildStartupCommand(
            packageName = "be.example",
            packageCodePath = "/data/app/example/base.apk",
            niceName = "example:root",
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
        assertTrue(command.contains("librootkotlinx: app_process relocation failed' >&2; exit 101;"))
    }

    @Test
    fun startupCommandResolvesAppProcessInRootBeforeWritingMarker() {
        val command = RootProcessLauncher.buildStartupCommand(
            packageName = "be.example",
            packageCodePath = "/data/app/example/base.apk",
            niceName = "example:root",
            markerPath = "/proc/self/fd/3",
            ownershipSocketName = "ownership",
            handoffAuthority = "authority",
            handoffToken = "token",
            appProcess = null,
            shouldRelocate = false,
            relocationToken = "",
        )

        assertTrue(command.contains("app_process_path=\"$(/system/bin/readlink -f '/proc/"))
        assertTrue(command.contains("librootkotlinx: app_process resolution failed' >&2; exit 102;"))
        assertEquals(2, "exit 103 ;;".toRegex().findAll(command).count())
        assertTrue(command.indexOf("app_process_path=") < command.indexOf("librootkotlinx-started"))
        assertTrue(command.contains("exec \"${'$'}app_process_path\" -Xnoimage-dex2oat"))
        assertTrue(command.contains("exec runcon u:r:su:s0 \"${'$'}app_process_path\" -Xnoimage-dex2oat"))
    }

    @Test
    fun startupMarkerCarriesCanonicalPositivePid() {
        assertEquals(123, RootProcessLauncher.parseStartupShellPid("librootkotlinx-started:123"))
        for (marker in arrayOf(
            "librootkotlinx-started",
            "librootkotlinx-started:",
            "librootkotlinx-started:0",
            "librootkotlinx-started:01",
            "librootkotlinx-started:-1",
            "librootkotlinx-started:+1",
            "librootkotlinx-started: 1",
            "librootkotlinx-started:1 ",
            "librootkotlinx-started:2147483648",
            "unexpected:1",
        )) assertNull(marker, RootProcessLauncher.parseStartupShellPid(marker))
    }

    @Test
    fun onlyControlledPreMarkerExitsAreReportable() {
        val controlled = RootProcessLauncher.startupExitFailure(
            RootProcessExit.STARTUP_MARKER_OPEN_FAILED.code,
            ": details",
        )
        assertTrue(controlled is IOException)
        assertEquals(
            "Root process exited with code 100 (startup marker open failed) before startup marker: details",
            controlled.message,
        )

        val unknown = RootProcessLauncher.startupExitFailure(1, ": denied")
        assertTrue(unknown is NoShellException)
        assertEquals("Root shell exited unexpectedly with code 1: denied", unknown.message)
    }
}
