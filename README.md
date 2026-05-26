# librootkotlinx

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
* 100% event driven via coroutines, no blocking calls (daemon setup excluded due to blocking code in libsu)
* Persistent root session that closes itself on inactive (optional and configurable)
* libsu RootService backend with Binder IPC, including normal `ParcelFileDescriptor` passing

## Comparison with [libsu](https://github.com/topjohnwu/libsu)

librootkotlinx 2.0 now uses libsu's RootService backend.
It keeps this library's coroutine-oriented `RootCommand` and `RootSession` API while delegating root process startup and Binder IPC compatibility to libsu.
You may think of this library as an unofficial `libsu-ktx`.

* librootkotlinx supports only API 23+ instead of 19+ for libsu.
* librootkotlinx exposes suspend functions and Kotlin Flow instead of requiring consumers to write AIDL.
* librootkotlinx supports more robust error surfacing and handling than libsu.
* librootkotlinx is strict one client to one server. Multiple client processes/users are unsupported.
* librootkotlinx depends on `com.github.topjohnwu.libsu:service`, so consumers need the JitPack repository available.
* libsu has additional APIs such as shell helpers and remote file system support; librootkotlinx intentionally keeps
  those outside its public API.
