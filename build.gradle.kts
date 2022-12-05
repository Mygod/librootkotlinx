plugins {
    val androidGradleVersion = "7.3.1"
    id("com.android.application") version androidGradleVersion apply false
    id("com.android.library") version androidGradleVersion apply false
    id("com.github.ben-manes.versions") version "0.44.0"
    id("org.jetbrains.kotlin.android") version "1.7.21" apply false
}

buildscript {
    dependencies {
        classpath("com.vanniktech:gradle-maven-publish-plugin:0.22.0")
    }
}
