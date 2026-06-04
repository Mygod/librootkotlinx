package be.mygod.librootkotlinx.impl

import android.content.Context
import android.os.Build
import android.os.Debug
import android.system.Os
import java.io.File

internal object AppProcess {
    val myExe get() = "/proc/${Os.getpid()}/exe"

    /**
     * Mirrors libsu's Android Studio startup-agent warning probe. Optimized consumer builds strip this diagnostic path.
     *
     * libsu source:
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/core/src/main/java/com/topjohnwu/superuser/internal/Utils.java#L127-L131
     */
    fun hasStartupAgents(context: Context) = Build.VERSION.SDK_INT >= 30 &&
            File(context.codeCacheDir, "startup_agents").isDirectory

    /**
     * app_process relocation workaround for Samsung/old-Android exec failures.
     * This stays API 23-25; modern Android app_process normally lives outside /data, and the API 29+
     * APEX/linker-config relocation path is not worth owning without a realistic trigger.
     */
    fun relocateScript(token: String): Pair<String, String> {
        val relocated = "/dev/app_process_$token"
        return "[ -f $relocated ] || { cp $myExe $relocated && chmod 700 $relocated; } || exit 1\n" to relocated
    }

    /**
     * Builds the app_process command from libsu RootServiceManager's launch contract. Intentional changes from libsu:
     * no root-main jar, no RootServerMain, no broadcast manager, the base APK supplies only the bootstrap class, and
     * the overrideable VM option string is inserted raw before /system/bin.
     *
     * libsu source:
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L191-L233
     */
    fun launchString(
        packageCodePath: String,
        clazz: String,
        appProcess: String,
        niceName: String,
        appProcessVmOption: String?,
    ): String {
        val debugParams = if (Debug.isDebuggerConnected()) when (Build.VERSION.SDK_INT) {
            in 29..Int.MAX_VALUE -> "-XjdwpProvider:adbconnection -XjdwpOptions:suspend=n,server=y "
            28 -> "-XjdwpProvider:adbconnection -XjdwpOptions:suspend=n,server=y -Xcompiler-option --debuggable "
            27 -> "-Xrunjdwp:transport=dt_android_adb,suspend=n,server=y -Xcompiler-option --debuggable "
            else -> ""
        } else ""
        return buildString {
            append("CLASSPATH=${quote(packageCodePath)} exec $appProcess $debugParams-Xnoimage-dex2oat ")
            appProcessVmOption?.let {
                append(it)
                append(' ')
            }
            append("/system/bin ${quote("--nice-name=$niceName")} $clazz")
        }
    }

    /**
     * Single-quote escaping compatible with libsu ShellUtils.escapedString, kept local because libsu is no longer a
     * dependency.
     *
     * libsu source:
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/core/src/main/java/com/topjohnwu/superuser/ShellUtils.java#L124-L137
     */
    fun quote(value: String) = StringBuilder("'").apply {
        value.forEach { if (it == '\'') append("'\\''") else append(it) }
    }.append('\'')
}
