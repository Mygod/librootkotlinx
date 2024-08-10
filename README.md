# librootkotlinx

[![CircleCI](https://circleci.com/gh/Mygod/librootkotlinx.svg?style=shield)](https://circleci.com/gh/Mygod/librootkotlinx)
[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![Language: Kotlin](https://img.shields.io/github/languages/top/Mygod/librootkotlinx.svg)](https://github.com/Mygod/librootkotlinx/search?l=kotlin)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/ae00f3cc581f4222a126ffafeeb70987)](https://www.codacy.com/gh/Mygod/librootkotlinx/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=Mygod/librootkotlinx&amp;utm_campaign=Badge_Grade)
[![License](https://img.shields.io/github/license/Mygod/librootkotlinx.svg)](LICENSE)

Run rooted Kotlin JVM code made super easy with coroutines and parcelize!
Check out demo at `app` to see just how easy it is.
Also check out more complicated demos:
* [PoGo+LE](https://github.com/Mygod/pogoplusle)
* [VPN Hotspot](https://github.com/Mygod/VPNHotspot) (how this library started)

Use it now!
`be.mygod.librootkotlinx:librootkotlinx:1.0.0+`
(see Releases page for latest version)

## Features

* 100% Kotlin with coroutines and `Parcelize`! Easy to use and virtually no boilerplate code (no aidl)
* Persistent root session that closes itself on inactive (optional and configurable)
* Supports running native code (API 23+)

## Comparison with [libsu](https://github.com/topjohnwu/libsu)

This project achieves morally the same thing as and ports compatibility code from libsu up to v6.0.0.
With that said, there are a few differences.

* librootkotlinx supports only API 21+ instead of 19+ for libsu.
* librootkotlinx is 100% Kotlin and much easier to use with coroutines,
  whereas libsu uses AIDL which involves heavy boilerplate usages.
* librootkotlinx is minimal and lightweight as additional features need to be manually enabled.
* librootkotlinx is more reliable since it minimizes the amount of private APIs used (see listed below).
  This is possible also because it does not enable all features by default.
* Out of the box, librootkotlinx is more secure since it uses Unix pipe instead of AIDL for IPC.
* librootkotlinx works around not able to `exec` on certain devices running API 21-25.
  (See `RootServer.init#shouldRelocate` if you need this feature.)
* libsu has some additional features such as remote file system.

## Private APIs used

The following private platform APIs are invoked if you use `shouldRelocate = true` on API 29+.
(So never under normal circumstances.)
API restrictions are updated up to [SHA-256 checksum `2886a24b6382be8751e86e3c355516c448987c3b0550eb8bb906a34490cfaa3c`](https://dl.google.com/developers/android/tm/non-sdk/hiddenapi-flags.csv).

* (relocated mode, API 29+) `Landroid/os/SystemProperties;->get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;,sdk,system-api,test-api`
* (relocated mode, API 29+) `Landroid/os/SystemProperties;->getBoolean(Ljava/lang/String;Z)Z,sdk,system-api,test-api`
* (for JNI) `Ldalvik/system/BaseDexClassLoader;->pathList:Ldalvik/system/DexPathList;,unsupported`
* (for JNI) `Ldalvik/system/DexPathList;->nativeLibraryDirectories:Ljava/util/List;,unsupported`
* (relocated mode, API 29+) `Ldalvik/system/VMRuntime;->getCurrentInstructionSet()Ljava/lang/String;,core-platform-api,unsupported`
* (relocated mode, API 29+) `Ldalvik/system/VMRuntime;->getRuntime()Ldalvik/system/VMRuntime;,core-platform-api,unsupported`
