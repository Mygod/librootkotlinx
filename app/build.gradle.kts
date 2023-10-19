plugins {
    id("com.android.application")
    kotlin("android")
    id("kotlin-parcelize")
}

android {
    namespace = "be.mygod.librootkotlinx.demo"
    compileSdk = 34
    defaultConfig {
        applicationId = "be.mygod.librootkotlinx.demo"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val javaVersion = JavaVersion.VERSION_11
    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    kotlinOptions.jvmTarget = javaVersion.toString()
    buildTypes {
        release {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures.buildConfig = true
}

dependencies {
    implementation(project(":lib"))
    implementation("androidx.activity:activity:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
