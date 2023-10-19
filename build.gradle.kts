plugins {
    val androidGradleVersion = "8.1.2"
    id("com.android.application") version androidGradleVersion apply false
    id("com.android.library") version androidGradleVersion apply false
    id("com.github.ben-manes.versions") version "0.49.0"
    id("org.jetbrains.kotlin.android") version "1.9.10" apply false
}

buildscript {
    dependencies {
        classpath("com.vanniktech:gradle-maven-publish-plugin:0.25.3")
    }
}
