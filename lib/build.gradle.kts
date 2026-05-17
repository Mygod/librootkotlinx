plugins {
    id("com.android.library")
    id("me.tylerbwong.gradle.metalava")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "be.mygod.librootkotlinx"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        aidl = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    val javaVersion = JavaVersion.VERSION_1_8
    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
}

metalava {
    filename.set("api/current.txt")
    arguments.addAll(
        "--stub-packages",
        "be.mygod.librootkotlinx:be.mygod.librootkotlinx.io:be.mygod.librootkotlinx.net",
    )
}

dependencies {
    api("androidx.collection:collection:1.6.0")
    api("com.github.topjohnwu.libsu:service:6.0.0")
    api("io.ktor:ktor-io-jvm:3.4.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
