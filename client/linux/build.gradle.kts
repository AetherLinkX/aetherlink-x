import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.0"
}

group = "com.palazik"
// Keep in sync with android app_version (jpackage needs plain MAJOR.MINOR.PATCH)
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.animation)
    // Icon artifacts are frozen at 1.7.3 upstream — same Icons.Rounded set the Android app uses.
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.1")

    implementation("org.json:json:20240303")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR encode/decode (same library the Android app uses)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.palazik.vpn.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "AetherLinkXClient"
            packageVersion = project.version.toString()
            description = "AetherLink X and Xray proxy client for Linux"
            vendor = "AetherLinkX"

            // xray / tun2socks / geo files are downloaded into resources/linux-x64 by CI
            // and land in <dist>/app/resources at runtime
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                menuGroup = "Network"
            }

            modules("java.naming", "jdk.crypto.ec", "java.net.http")
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
