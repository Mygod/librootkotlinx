plugins {
    id("com.android.application")
    kotlin("android")
    id("kotlin-parcelize")
}

android {
    namespace = "be.mygod.librootkotlinx.demo"
    compileSdk = 33
    defaultConfig {
        applicationId = "be.mygod.librootkotlinx.demo"
        minSdk = 21
        targetSdk = 33
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
}

dependencies {
    implementation(project(":lib"))
    implementation("androidx.activity:activity:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
