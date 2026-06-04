package be.mygod.librootkotlinx.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class AppProcessTest {
    @Test
    fun launchStringOmitsNullAppProcessVmOption() {
        assertEquals(
            "CLASSPATH='/data/app/example/base.apk' exec /system/bin/app_process -Xnoimage-dex2oat " +
                    "/system/bin '--nice-name=example:root' be.example.RootMain",
            AppProcess.launchString(
                packageCodePath = "/data/app/example/base.apk",
                clazz = "be.example.RootMain",
                appProcess = "/system/bin/app_process",
                niceName = "example:root",
                appProcessVmOption = null,
            ),
        )
    }

    @Test
    fun launchStringPassesAppProcessVmOptionRawBeforeSystemBin() {
        assertEquals(
            "CLASSPATH='/data/app/example/base.apk' exec /system/bin/app_process -Xnoimage-dex2oat " +
                    "-Dkotlinx.coroutines.debug=on -Dquoted='client value' /system/bin " +
                    "'--nice-name=example:root' be.example.RootMain",
            AppProcess.launchString(
                packageCodePath = "/data/app/example/base.apk",
                clazz = "be.example.RootMain",
                appProcess = "/system/bin/app_process",
                niceName = "example:root",
                appProcessVmOption = "-Dkotlinx.coroutines.debug=on -Dquoted='client value'",
            ),
        )
    }
}
