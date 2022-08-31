plugins {
    val androidGradleVersion = "7.2.2"
    id("com.android.application") version androidGradleVersion apply false
    id("com.android.library") version androidGradleVersion apply false
    id("com.github.ben-manes.versions") version "0.42.0"
    id("org.jetbrains.kotlin.android") version "1.7.10" apply false
}

buildscript {
    dependencies {
        classpath("com.vanniktech:gradle-maven-publish-plugin:0.21.0")
    }
}
