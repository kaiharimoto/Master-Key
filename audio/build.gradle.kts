plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.kaiharimoto.masterkey.audio"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        // Single ABI keeps one release asset for the in-app updater while still
        // getting the size win of not shipping four architectures. Every tablet
        // from the last several years is arm64, and Android 15+ is 64-bit only.
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Pinned: the CI runner image defaults to NDK 27, but AGP 9.3 expects 28.x.
    // r28+ also emits 16 KB-page-aligned libraries by default, which Android 16
    // warns about and Android 17 can be configured to reject outright.
    ndkVersion = "28.2.13676358"

    buildFeatures {
        // Required to consume Oboe's native headers/libs from its AAR.
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.oboe)
    implementation(libs.kotlinx.coroutines.android)
}
