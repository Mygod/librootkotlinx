package be.mygod.librootkotlinx.impl

import android.os.Build
import android.os.Debug
import android.system.Os

internal object RootProcessAppProcess {
    val myExe get() = "/proc/${Os.getpid()}/exe"

    /**
     * app_process relocation workaround for Samsung/old-Android exec failures.
     * This stays API 23-25; modern Android app_process normally lives outside /data, and the API 29+
     * APEX/linker-config relocation path is not worth owning without a realistic trigger.
     */
    fun relocateScript(token: String): Pair<StringBuilder, String> {
        val relocated = "/dev/app_process_$token"
        return StringBuilder().apply {
            appendLine("[ -f ${ShellScript.quote(relocated)} ] || { " +
                    "mkdir -p '/dev' && " +
                    "cp $myExe ${ShellScript.quote(relocated)} && " +
                    "chmod 700 ${ShellScript.quote(relocated)}; } || exit 1")
        } to relocated
    }

    /**
     * Builds the app_process command from libsu RootServiceManager's launch contract. Intentional changes from libsu:
     * no root-main jar, no RootServerMain, no broadcast manager, the base APK supplies only the bootstrap class, and
     * every generated shell value is quoted.
     *
     * libsu source:
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L191-L233
     */
    fun launchString(
        packageCodePath: String,
        clazz: String,
        appProcess: String,
        niceName: String,
    ): String {
        val debugParams = if (Debug.isDebuggerConnected()) when (Build.VERSION.SDK_INT) {
            in 29..Int.MAX_VALUE -> listOf("-XjdwpProvider:adbconnection", "-XjdwpOptions:suspend=n,server=y")
            28 -> listOf(
                "-XjdwpProvider:adbconnection",
                "-XjdwpOptions:suspend=n,server=y",
                "-Xcompiler-option",
                "--debuggable",
            )
            27 -> listOf(
                "-Xrunjdwp:transport=dt_android_adb,suspend=n,server=y",
                "-Xcompiler-option",
                "--debuggable",
            )
            else -> emptyList()
        } else emptyList()
        return listOf(
            "CLASSPATH=${ShellScript.quote(packageCodePath)}",
            "exec",
            ShellScript.quote(appProcess),
            *debugParams.toTypedArray(),
            "-Xnoimage-dex2oat",
            "/system/bin",
            ShellScript.quote("--nice-name=$niceName"),
            clazz,
        ).joinToString(" ")
    }
}
