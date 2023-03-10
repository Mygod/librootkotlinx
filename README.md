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
`be.mygod.librootkotlinx:librootkotlinx:1.0.2`

## Features

* 100% Kotlin with coroutines and Parcelize! Easy to use and virtually no boilerplate code (no aidl)
* Persistent root session that closes itself on inactive (optional and configurable)
* Works around not able to `exec` on certain devices running Android 5-7.1
  (See `RootServer.init#shouldRelocate` if you need this feature)  

## Private APIs used

The following private platform APIs are invoked if you use `shouldRelocate = true` on Android 10+.
(So never under normal circumstances.)
API restrictions are updated up to [SHA-256 checksum `2886a24b6382be8751e86e3c355516c448987c3b0550eb8bb906a34490cfaa3c`](https://dl.google.com/developers/android/tm/non-sdk/hiddenapi-flags.csv).

* `Landroid/os/SystemProperties;->get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;,sdk,system-api,test-api`
* `Landroid/os/SystemProperties;->getBoolean(Ljava/lang/String;Z)Z,sdk,system-api,test-api`
* `Ldalvik/system/VMRuntime;->getCurrentInstructionSet()Ljava/lang/String;,core-platform-api,unsupported`
* `Ldalvik/system/VMRuntime;->getRuntime()Ldalvik/system/VMRuntime;,core-platform-api,unsupported`
