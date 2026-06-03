# librootkotlinx

[![CI](https://github.com/Mygod/librootkotlinx/actions/workflows/ci.yml/badge.svg)](https://github.com/Mygod/librootkotlinx/actions/workflows/ci.yml)
[![Android 6-16.1](https://img.shields.io/badge/Android-6--16.1-3DDC84?logo=android&logoColor=white)](lib/build.gradle.kts)

Run rooted Kotlin JVM code made super easy with coroutines and parcelize!
Check out demo at `app` to see just how easy it is.
Also check out more complicated demos:
* [PoGo+LE](https://github.com/Mygod/pogoplusle)
* [VPN Hotspot](https://github.com/Mygod/VPNHotspot) (how this library started)

Use it now!
`be.mygod.librootkotlinx:librootkotlinx:2.0.0+`
(see Releases page for latest version)

## Features

* 100% Kotlin public API with coroutines and `Parcelize`! Easy to use and virtually no boilerplate code/aidl
* 100% event driven via coroutines, no blocking calls
* Persistent root session that closes itself on inactive (optional and configurable)
* Supports `IBinder` and `ParcelFileDescriptor` passing via a Binder IPC backend

## Comparison with [libsu](https://github.com/topjohnwu/libsu)

librootkotlinx is inspired by and heavily borrows libsu's RootService backend design.

* librootkotlinx supports only API 23+ for nonblocking IO instead of 19+ for libsu.
* librootkotlinx exposes suspend functions and Kotlin Flow instead of requiring consumers to write AIDL.
* librootkotlinx uses content provider for Binder handoff, which is more robust and less prone to system broadcast pressure than libsu's broadcast path, and more lightweight and more secure compared to libsu's daemon service path.
* librootkotlinx supports more robust error surfacing, handling and recovery.
  In contrast, libsu quietly swallows Exceptions/failures, does not support app-provided logger, and may block retry until the entire process is restarted.
* librootkotlinx is cancellation aware whereas libsu provides no such path and has a fixed non-negotiable 10-second timeout as a workaround.
* librootkotlinx does not provide a root shell API and thus does not need to keep an unused root shell process hanging around.
* librootkotlinx is coroutine/Ktor-first and uses no blocking I/O.
* librootkotlinx uses Linux local socket to guarantee server death once the client process is gone, which is more reliable than Android APIs used by libsu. 
* librootkotlinx is strict one client to one server. Multiple client processes/users are unsupported.
* libsu has additional APIs such as shell helpers and remote file system support.

## Private APIs used / Assumptions for Android customizations

_a.k.a. things that can go wrong if root startup doesn't work._

This is a list of stuff that might impact this library's functionality if unavailable.
This is only meant to be an index.
You can read more in the source code.
API restrictions are updated up to [SHA-256 checksum `9102af02fe6ab68b92464bdff5e5b09f3bd62c65d1130aaf85d3296f17d38074`](https://github.com/Mygod/hiddenapi/commit/2f90e9da30976febeb0630cba48c4da0116c323d).

Greylisted/blacklisted APIs or internal constants: (some constants are hardcoded or implicitly used)

* (API 26+) `Landroid/app/ActivityManager;->getService()Landroid/app/IActivityManager;,unsupported`
* (API 23-25) `Landroid/app/ActivityManagerNative;->getDefault()Landroid/app/IActivityManager;,unsupported`
* `Landroid/app/ActivityThread;->getSystemContext()Landroid/app/ContextImpl;,unsupported`
* `Landroid/app/ActivityThread;->systemMain()Landroid/app/ActivityThread;,unsupported`
* (API 26+) `Landroid/app/ContentProviderHolder;->provider:Landroid/content/IContentProvider;,unsupported`
* (API 23-25) `Landroid/app/IActivityManager$ContentProviderHolder;->provider:Landroid/content/IContentProvider;`
* (API 29+) `Landroid/app/IActivityManager;->getContentProviderExternal(Ljava/lang/String;ILandroid/os/IBinder;Ljava/lang/String;)Landroid/app/ContentProviderHolder;,blocked`
* (API 23-28) `Landroid/app/IActivityManager;->removeContentProviderExternal(Ljava/lang/String;Landroid/os/IBinder;)V,max-target-r`
* (API 29+) `Landroid/app/IActivityManager;->removeContentProviderExternalAsUser(Ljava/lang/String;Landroid/os/IBinder;I)V,blocked`
* (API 31+) `Landroid/content/IContentProvider;->call(Landroid/content/AttributionSource;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;,blocked`
* (API 23-28) `Landroid/content/IContentProvider;->call(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;,max-target-q`
* (API 29) `Landroid/content/IContentProvider;->call(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;`
* (API 30) `Landroid/content/IContentProvider;->call(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;`
* (API 24+) `Landroid/content/res/Resources;->getImpl()Landroid/content/res/ResourcesImpl;,unsupported`
* `Landroid/content/res/Resources;->mSystem:Landroid/content/res/Resources;,unsupported`
* (API 24+) `Landroid/content/res/Resources;->setImpl(Landroid/content/res/ResourcesImpl;)V,unsupported`
* (API 23) `Ljava/lang/ProcessManager$ProcessInputStream;->fd:Ljava/io/FileDescriptor;`
* (API 23) `Ljava/lang/ProcessManager$ProcessOutputStream;->fd:Ljava/io/FileDescriptor;`
* (API 23) `Landroid/os/UserHandle;-><init>(I)V,unsupported`
* `Llibcore/io/IoUtils;->setBlocking(Ljava/io/FileDescriptor;Z)V,core-platform-api,unsupported`

<details>
<summary>Hidden whitelisted APIs: (same catch as above, however, things in this list are less likely to be broken)</summary>

* `Landroid/app/ContextImpl;->createPackageContextAsUser(Ljava/lang/String;ILandroid/os/UserHandle;)Landroid/content/Context;,sdk,system-api,test-api`
* `Landroid/content/Context;->createPackageContextAsUser(Ljava/lang/String;ILandroid/os/UserHandle;)Landroid/content/Context;,sdk,system-api,test-api`
* (API 24+) `Landroid/os/UserHandle;->of(I)Landroid/os/UserHandle;,sdk,system-api,test-api`

</details>

Optional hidden APIs used only for nonblocking optimization/optional helper methods:

* (API 31+) `Landroid/os/Process;->openPidFd(II)Ljava/io/FileDescriptor;,blocked`
* (API 24+) `Ljava/lang/UNIXProcess;->pid:I,unsupported`
* (API 23) `Ljava/lang/ProcessManager$ProcessImpl;->pid:I`

Other:

* The root process calls the app-owned handoff provider through
  [`ActivityManager.getService()`](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/java/android/app/ActivityManager.java#4199),
  [`IActivityManager.getContentProviderExternal(...)`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/app/IActivityManager.aidl#317),
  [`IActivityManager.removeContentProviderExternalAsUser(...)`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/app/IActivityManager.aidl#322),
  and `ContentProviderHolder.provider`, which is
  [`IActivityManager.ContentProviderHolder.provider`](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/IActivityManager.java#474)
  on API 23-25 and
  [`ContentProviderHolder.provider`](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/java/android/app/ContentProviderHolder.java#33)
  on API 26+.
  The API 23-28 external-provider acquisition/release signatures are
  [`getContentProviderExternal(..., token)`](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/IActivityManager.java#145)
  and [`removeContentProviderExternal(...)`](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/IActivityManager.java#148).
  On API 23-28, the release path uses
  [`UserHandle.getCallingUserId()`](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java#11743),
  so secondary-user handoff is refused before external-provider acquisition.
* Provider handoff uses
  [`IContentProvider.call(AttributionSource, ...)`](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/content/IContentProvider.java#123)
  with [`AttributionSource.myAttributionSource()`](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/content/AttributionSource.java#195),
  API 30
  [`IContentProvider.call(callingPkg, attributionTag, authority, ...)`](https://android.googlesource.com/platform/frameworks/base/+/android-11.0.0_r1/core/java/android/content/IContentProvider.java#118),
  API 29
  [`IContentProvider.call(callingPkg, authority, ...)`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/content/IContentProvider.java#82),
  and the API 23-28
  [`IContentProvider.call(callingPkg, method, ...)`](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/content/IContentProvider.java#58).
  API 29+ must pass authority because
  [`ContentProvider.Transport.call(...)`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/content/ContentProvider.java#470)
  validates it before dispatch.
* Root package context creation assumes
  [`ActivityThread.systemMain()`](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/ActivityThread.java#5129),
  [`ActivityThread.getSystemContext()`](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/ActivityThread.java#1792),
  [`ContextImpl.createPackageContextAsUser(...)`](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/ContextImpl.java#2120),
  and [`Context.createPackageContextAsUser(...)`](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/content/Context.java#3346).
* The LG `IntegrityManager` workaround replaces `Resources.mSystem` and preserves the original `ResourcesImpl`
  through [`Resources.getImpl()`](https://android.googlesource.com/platform/frameworks/base/+/android-7.0.0_r1/core/java/android/content/res/Resources.java#271)
  and [`Resources.setImpl(...)`](https://android.googlesource.com/platform/frameworks/base/+/android-7.0.0_r1/core/java/android/content/res/Resources.java#248),
  matching [libsu's root-service bootstrap](https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/jar/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java#L145-L156).
* Shell command construction keeps
  [libsu `RootServiceManager`'s app_process command contract](https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L191-L233),
  while replacing libsu's root-main jar, `RootServerMain`, and broadcast handoff with a base-APK bootstrap classpath
  shape derived from [librootkotlinx v1 `AppProcess.launchString`](https://github.com/Mygod/librootkotlinx/blob/06701fd7d6f2fc115ee90cb47ee7105d94a6ddd3/lib/src/main/java/be/mygod/librootkotlinx/AppProcess.kt#L119-L136).
* `ProcessPipes.startPipes` uses
  [`ProcessBuilder.redirectInput(File)`](https://android.googlesource.com/platform/libcore/+/android-7.0.0_r1/ojluni/src/main/java/java/lang/ProcessBuilder.java#728),
  [`redirectOutput(File)`](https://android.googlesource.com/platform/libcore/+/android-7.0.0_r1/ojluni/src/main/java/java/lang/ProcessBuilder.java#747),
  and [`redirectError(File)`](https://android.googlesource.com/platform/libcore/+/android-7.0.0_r1/ojluni/src/main/java/java/lang/ProcessBuilder.java#766)
  on API 24+, even though Android SDK metadata marks these APIs as 26+.
  The API 23 fallback duplicates descriptors from process streams. AOSP's API 23 implementation exposes
  [`ProcessInputStream.fd`](https://android.googlesource.com/platform/libcore/+/android-6.0.0_r1/luni/src/main/java/java/lang/ProcessManager.java#345)
  and
  [`ProcessOutputStream.fd`](https://android.googlesource.com/platform/libcore/+/android-6.0.0_r1/luni/src/main/java/java/lang/ProcessManager.java#371);
  this library first uses the public `FileInputStream`/`FileOutputStream` descriptor access when possible and keeps
  named-field reflection as the fallback for those API 23 stream classes.
* `RootProcessBootstrap` starts from the base APK classpath, then asks the framework-created package context for the
  app class loader. That lets Android assemble base app code and native library paths through the normal package-loading
  path instead of duplicating `LoadedApk.makePaths(...)`.
  The root side does not support code or Parcelables packaged in split APKs or dynamic feature modules. Consumers must
  package `librootkotlinx` and every `RootCommand`, `RootFlow`, `Parcelable`, exception class, and dependency that may
  be loaded or unparceled in the root process in the base APK. This includes both ordinary and isolated feature splits.

System/root command assumptions:

The following Android system binaries or shell commands are assumed to be bundled and executable:

* `/system/bin/su`, `/system/xbin/su`, `/sbin/su`, `/debug_ramdisk/su`, `/data/adb/ksu/bin/su`,
  `/data/adb/ap/bin/su`, or `su`;
* `/system/bin/app_process` as the fallback root `app_process` executable in case discovery fails;
* `mkdir`, `cp`, `chmod`, `printf`, and shell `exec`.
