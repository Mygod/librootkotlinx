plugins {
    val androidGradleVersion = "9.2.0"
    id("com.android.application") version androidGradleVersion apply false
    id("com.android.library") version androidGradleVersion apply false
    id("com.github.ben-manes.versions") version "0.54.0"
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.3.21" apply false
}

buildscript {
    dependencies {
        classpath("com.vanniktech:gradle-maven-publish-plugin:0.36.0")
    }
}
