plugins {
    val androidGradleVersion = "8.0.1"
    id("com.android.application") version androidGradleVersion apply false
    id("com.android.library") version androidGradleVersion apply false
    id("com.github.ben-manes.versions") version "0.46.0"
    id("org.jetbrains.kotlin.android") version "1.8.21" apply false
}

buildscript {
    dependencies {
        classpath("com.vanniktech:gradle-maven-publish-plugin:0.25.2")
    }
}
