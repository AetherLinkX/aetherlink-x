import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

// ── Release signing ──────────────────────────────────────────────────────────
// Credentials come from keystore.properties (local, gitignored) OR environment
// variables (CI). If none are present the release stays unsigned, so CI without
// secrets still builds exactly like before.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "KEYSTORE_FILE")
val hasReleaseSigning = releaseStoreFile != null

android {
    namespace         = "com.palazik.vpn"
    compileSdk        = 37

    defaultConfig {
        applicationId = "io.aetherlinkx.client"
        minSdk        = 26
        targetSdk     = 36
        versionCode   = 28
        versionName   = "0.8.0-alx-native-preview.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Only real-device ABIs — dropping x86/x86_64 saves ~36MB from libgojni.so
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // ABI splits: per-ABI APKs (~40MB each) + universal fat APK for TG/direct share
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true   // also produce a universal APK
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile     = file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias      = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword   = signingValue("keyPassword", "KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed when keystore.properties / CI secrets are present; unsigned otherwise.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable        = true
            // CI uses the same private update key for every published debug APK.
            // This makes development Releases safely upgradeable in place.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            // Enable splits for debug too so CI sends small per-ABI APKs to TG
            splits {
                abi {
                    isEnable = true
                    reset()
                    include("arm64-v8a", "armeabi-v7a")
                    isUniversalApk = true
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21 } }
    buildFeatures { compose = true }

    sourceSets {
        getByName("main") {
            // Built from the pinned upstream hev-socks5-tunnel source in CI.
            jniLibs.srcDirs("libs")
        }
    }

    lint {
        disable += setOf("BlockedPrivateApi")
        checkReleaseBuilds = false
        abortOnError       = false
    }
}

dependencies {
    // libv2ray.aar + go.jar bundled inside it — geodata baked in assets at build time
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3.expressive)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
