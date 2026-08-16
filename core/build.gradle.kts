plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// :core is deliberately a pure-JVM module with no Android dependency.
// All the logic worth unit-testing lives here — MIDI parsing, the tempo map,
// auto range selection, MusicXML parsing — so its tests run in milliseconds
// without Robolectric or a device.
//
// Bytecode targets 17 to match the Android modules, but we compile with whatever
// JDK is running (21 locally and in CI) rather than pinning a toolchain — that
// avoids needing a second JDK installed just to build one module.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.ktmidi)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
