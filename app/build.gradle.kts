import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Version derives from the git tag via a single formula that is mirrored exactly
// in the in-app updater (VersionCodes.kt). Tag v1.2.3 -> versionCode 10203.
// If you change the formula here, change it there too.
val appVersionName = (findProperty("appVersionName") as String?) ?: "0.1.0"
val appVersionCode = (findProperty("appVersionCode") as String?)?.toInt()
    ?: appVersionName.split(".").let { p ->
        require(p.size == 3) { "appVersionName must be MAJOR.MINOR.PATCH, got '$appVersionName'" }
        p[0].toInt() * 10000 + p[1].toInt() * 100 + p[2].toInt()
    }

// The signing key is committed on purpose — see KEYS.md. It must never be
// regenerated, or updates can no longer install over an existing install.
val keystorePropsFile = rootProject.file("keystore/signing.properties")

android {
    namespace = "dev.kaiharimoto.masterkey"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.kaiharimoto.masterkey"
        minSdk = 26
        // MUST stay explicit. Under AGP 9 an unset targetSdk silently follows
        // compileSdk, which would opt us into Android 17 background-audio
        // hardening — where audio calls fail with no exception and no log.
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        // One ABI, one release asset. The in-app updater depends on there being
        // exactly one APK to download, and ABI splits would additionally force
        // per-ABI versionCodes, making "is this newer than what I'm running?"
        // ambiguous. Every tablet from the last several years is arm64, and
        // Android 15+ devices are 64-bit only.
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                val props = Properties().apply {
                    keystorePropsFile.inputStream().use { load(it) }
                }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
            // minSdk 26 means the v1 JAR signature is dead weight.
            // v3 is what makes future key rotation possible at all.
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":audio"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.documentfile)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
