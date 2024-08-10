plugins {
    val androidGradleVersion = "8.5.2"
    id("com.android.application") version androidGradleVersion apply false
    id("com.android.library") version androidGradleVersion apply false
    id("com.github.ben-manes.versions") version "0.51.0"
    id("org.jetbrains.kotlin.android") version "2.0.10" apply false
}

buildscript {
    dependencies {
        classpath("com.vanniktech:gradle-maven-publish-plugin:0.29.0")
    }
}
