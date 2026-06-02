package be.mygod.librootkotlinx.impl

import android.os.Build
import android.os.Debug
import android.system.Os
import be.mygod.librootkotlinx.Logger
import java.io.File
import java.io.IOException

internal object RootProcessAppProcess {
    /**
     * Reads the ART instruction set used by bionic's linker config fallback selection.
     *
     * AOSP source:
     * https://android.googlesource.com/platform/libcore/+/android-5.0.0_r1/libart/src/main/java/dalvik/system/VMRuntime.java#350
     * libsu contrast:
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/core/src/main/java/com/topjohnwu/superuser/internal/Utils.java#L201-L216
     */
    private val currentInstructionSet by lazy {
        val vmRuntime = Class.forName("dalvik.system.VMRuntime")
        val runtime = vmRuntime.getDeclaredMethod("getRuntime")(null)
        vmRuntime.getDeclaredMethod("getCurrentInstructionSet")(runtime) as String
    }

    /**
     * Reads platform properties used by bionic's linker config fallback selection.
     *
     * AOSP source:
     * https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/os/SystemProperties.java#60
     * https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/os/SystemProperties.java#110
     */
    private val systemProperties by lazy { Class.forName("android.os.SystemProperties") }
    private val isVndkLite by lazy {
        systemProperties.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.java)(
            null, "ro.vndk.lite", false) as Boolean
    }
    private val vndkVersion by lazy {
        systemProperties.getDeclaredMethod("get", String::class.java, String::class.java)(
            null, "ro.vndk.version", "") as String
    }

    /**
     * Follows bionic's generic linker config file lookup for relocated app_process.
     *
     * AOSP sources:
     * https://android.googlesource.com/platform/bionic/+/android-10.0.0_r1/linker/linker.cpp#4085
     * https://android.googlesource.com/platform/bionic/+/android-11.0.0_r1/linker/linker.cpp#3412
     */
    private val genericLdConfigFilePath: String get() {
        "/system/etc/ld.config.$currentInstructionSet.txt".let { if (File(it).isFile) return it }
        if (Build.VERSION.SDK_INT >= 30) "/linkerconfig/ld.config.txt".let {
            if (File(it).isFile) return it
            Logger.me.w("Failed to find generated linker configuration from \"$it\"")
        }
        if (isVndkLite) "/system/etc/ld.config.vndk_lite.txt".let {
            if (File(it).isFile) return it
        } else when (vndkVersion) {
            "", "current" -> { }
            else -> "/system/etc/ld.config.$vndkVersion.txt".let { if (File(it).isFile) return it }
        }
        return "/system/etc/ld.config.txt"
    }

    val myExe get() = "/proc/${Os.getpid()}/exe"
    private val myExeCanonical get() = try {
        File("/proc/self/exe").canonicalPath.also { require(!it.startsWith("/proc/")) { it } }
    } catch (e: Exception) {
        Logger.me.d("warning: couldn't resolve self exe", e)
        "/system/bin/app_process"
    }

    val shouldRelocateHeuristics get() = Build.VERSION.SDK_INT < 26 || myExeCanonical.startsWith("/data/")

    /**
     * app_process relocation workaround for Samsung/old-Android exec failures.
     * Unlike libsu RootServiceManager, this copied-binary path needs the API-29+ linker-config tmpfs branch so relocated app_process can still resolve platform libraries.
     *
     * AOSP source:
     * https://android.googlesource.com/platform/bionic/+/android-10.0.0_r1/linker/linker.cpp#4062
     * libsu contrast:
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L221-L224
     */
    fun relocateScript(token: String): Pair<StringBuilder, String> {
        val script = StringBuilder()
        val (baseDir, relocated) = if (Build.VERSION.SDK_INT >= 29) {
            val apexPath = "/apex/$token"
            script.appendLine("[ -d ${ShellScript.quote(apexPath)} ] || " +
                    "mkdir ${ShellScript.quote(apexPath)} && " +
                    "mount -t tmpfs -o size=1M tmpfs ${ShellScript.quote(apexPath)} || exit 1")
            val ldConfig = "$apexPath/etc/ld.config.txt"
            val masterLdConfig = genericLdConfigFilePath
            val section = try {
                File(masterLdConfig).useLines { findLinkerSection(it, myExeCanonical) }
            } catch (e: Exception) {
                Logger.me.w("Failed to locate system section", e)
                "system"
            }
            script.appendLine("[ -f ${ShellScript.quote(ldConfig)} ] || " +
                    "mkdir -p ${ShellScript.quote("$apexPath/etc")} && " +
                    "echo ${ShellScript.quote("dir.$section = $apexPath")} >${ShellScript.quote(ldConfig)} && " +
                    "cat ${ShellScript.quote(masterLdConfig)} >>${ShellScript.quote(ldConfig)} || exit 1")
            "$apexPath/bin" to "$apexPath/bin/app_process"
        } else "/dev" to "/dev/app_process_$token"
        script.appendLine("[ -f ${ShellScript.quote(relocated)} ] || " +
                "mkdir -p ${ShellScript.quote(baseDir)} && " +
                "cp $myExe ${ShellScript.quote(relocated)} && " +
                "chmod 700 ${ShellScript.quote(relocated)} || exit 1")
        return script to relocated
    }

    /**
     * Follows bionic linker_config parsing to find the namespace section for the current app_process path.
     *
     * AOSP source:
     * https://android.googlesource.com/platform/bionic/+/android-8.0.0_r1/linker/linker_config.cpp#194
     */
    fun findLinkerSection(lines: Sequence<String>, binaryRealPath: String): String {
        for (untrimmed in lines) {
            val line = untrimmed.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line[0] == '[' && line.last() == ']') break
            if (line.contains("+=")) continue
            val chunks = line.split('=', limit = 2)
            if (chunks.size < 2) {
                Logger.me.w("warning: couldn't parse invalid format: $line (ignoring this line)")
                continue
            }
            val name = chunks[0].trim()
            var value = chunks[1].trim()
            if (!name.startsWith("dir.")) {
                Logger.me.w("warning: unexpected property name \"$name\", " +
                        "expected format dir.<section_name> (ignoring this line)")
                continue
            }
            if (value.endsWith('/')) value = value.dropLast(1)
            if (value.isEmpty()) {
                Logger.me.w("warning: property value is empty (ignoring this line)")
                continue
            }
            try {
                value = File(value).canonicalPath
            } catch (e: IOException) {
                Logger.me.i("warning: path \"$value\" couldn't be resolved: ${e.message}")
            }
            if (binaryRealPath == value || binaryRealPath.startsWith("$value/")) return name.substring(4)
        }
        throw IllegalArgumentException("No valid linker section found")
    }

    /**
     * Builds the app_process command from libsu RootServiceManager's launch contract and librootkotlinx v1's direct app
     * APK classpath shape. Intentional changes from libsu: no root-main jar, no RootServerMain, no broadcast manager,
     * app APK is the only classpath entry, and every generated shell value is quoted.
     *
     * libsu source:
     * https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L191-L233
     */
    fun launchString(
        packageCodePath: String,
        packageNativeLibrarySearchPath: String? = null,
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
        val nativeLibraryPath = if (packageNativeLibrarySearchPath.isNullOrEmpty()) emptyList() else {
            // AOSP ZygoteInit.createPathClassLoader passes java.library.path into the PathClassLoader used by
            // app_process, and Runtime.loadLibrary0 then asks that loader to find JNI libraries:
            // https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/java/com/android/internal/os/ZygoteInit.java#520
            val javaLibraryPath = System.getProperty("java.library.path").orEmpty()
            listOf("-Djava.library.path=${if (javaLibraryPath.isEmpty()) packageNativeLibrarySearchPath else {
                "$packageNativeLibrarySearchPath:$javaLibraryPath"
            }}")
        }
        return listOf(
            "CLASSPATH=${ShellScript.quote(packageCodePath)}",
            "exec",
            ShellScript.quote(appProcess),
            *debugParams.toTypedArray(),
            *nativeLibraryPath.map(ShellScript::quote).toTypedArray(),
            "-Xnoimage-dex2oat",
            "/system/bin",
            ShellScript.quote("--nice-name=$niceName"),
            clazz,
        ).joinToString(" ")
    }
}
