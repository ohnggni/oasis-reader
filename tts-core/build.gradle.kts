import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 소리 내 읽기 logic with no Android in it, so that other apps can use it too (3.0).
// This module never depends on :app.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
