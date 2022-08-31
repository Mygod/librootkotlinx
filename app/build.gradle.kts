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
//    implementation 'androidx.core:core-ktx:1.7.0'
    implementation("androidx.activity:activity:1.5.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
//    implementation 'com.google.android.material:material:1.6.1'
//    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.4.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
}