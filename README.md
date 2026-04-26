# librootkotlinx

[![CircleCI](https://circleci.com/gh/Mygod/librootkotlinx.svg?style=shield)](https://circleci.com/gh/Mygod/librootkotlinx)
[![API](https://img.shields.io/badge/API-19%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=19)
[![Language: Kotlin](https://img.shields.io/github/languages/top/Mygod/librootkotlinx.svg)](https://github.com/Mygod/librootkotlinx/search?l=kotlin)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/ae00f3cc581f4222a126ffafeeb70987)](https://www.codacy.com/gh/Mygod/librootkotlinx/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=Mygod/librootkotlinx&amp;utm_campaign=Badge_Grade)
[![License](https://img.shields.io/github/license/Mygod/librootkotlinx.svg)](LICENSE)

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
* 100% event driven via suspend functions, no blocking calls (daemon setup excluded due to blocking code in libsu)
* Persistent root session that closes itself on inactive (optional and configurable)
* libsu RootService backend with Binder IPC, including normal `ParcelFileDescriptor` passing

## Comparison with [libsu](https://github.com/topjohnwu/libsu)

librootkotlinx 2.0 now uses libsu's RootService backend.
It keeps this library's coroutine-oriented `RootCommand` and `RootSession` API while delegating root process startup and Binder IPC compatibility to libsu.
You may think of this library as an unofficial `libsu-ktx`.

* librootkotlinx exposes suspend functions and coroutine channels instead of requiring consumers to write AIDL.
* librootkotlinx depends on `com.github.topjohnwu.libsu:service`, so consumers need the JitPack repository available.
* libsu has additional APIs such as shell helpers and remote file system support; librootkotlinx intentionally keeps
  those outside its public API.
