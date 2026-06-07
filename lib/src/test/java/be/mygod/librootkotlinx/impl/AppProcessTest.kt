package be.mygod.librootkotlinx.impl

import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import org.junit.Assert.assertEquals
import org.junit.Test

class AppProcessTest {
    @Test
    fun launchStringUsesDefaultVmOptions() {
        val values = arrayOf(
            DEBUG_PROPERTY_NAME,
            "kotlinx.coroutines.stacktrace.recovery",
            "kotlinx.coroutines.debug.enable.creation.stack.trace",
        )
        val previous = values.associateWith(System::getProperty)
        try {
            for (name in values) System.clearProperty(name)
            assertEquals(
                "CLASSPATH='/data/app/example/base.apk' exec /system/bin/app_process -Xnoimage-dex2oat " +
                        "/system/bin '--nice-name=example:root' be.example.RootMain",
                AppProcess.launchString(
                    packageCodePath = "/data/app/example/base.apk",
                    clazz = "be.example.RootMain",
                    appProcess = "/system/bin/app_process",
                    niceName = "example:root",
                ),
            )
        } finally {
            for ((name, value) in previous) {
                if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            }
        }
    }

    @Test
    fun launchStringCopiesExplicitCoroutinePropertiesWithQuotedValues() {
        val values = arrayOf(
            DEBUG_PROPERTY_NAME to "on",
            "kotlinx.coroutines.stacktrace.recovery" to "false",
            "kotlinx.coroutines.debug.enable.creation.stack.trace" to "client's value",
        )
        val previous = values.associate { it.first to System.getProperty(it.first) }
        try {
            for ((name, value) in values) System.setProperty(name, value)
            assertEquals(
                "CLASSPATH='/data/app/example/base.apk' exec /system/bin/app_process -Xnoimage-dex2oat " +
                        "-Dkotlinx.coroutines.debug='on' -Dkotlinx.coroutines.stacktrace.recovery='false' " +
                        "-Dkotlinx.coroutines.debug.enable.creation.stack.trace='client'\\''s value' /system/bin " +
                        "'--nice-name=example:root' be.example.RootMain",
                AppProcess.launchString(
                    packageCodePath = "/data/app/example/base.apk",
                    clazz = "be.example.RootMain",
                    appProcess = "/system/bin/app_process",
                    niceName = "example:root",
                ),
            )
        } finally {
            for ((name, value) in previous) {
                if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            }
        }
    }
}
