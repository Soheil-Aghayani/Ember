import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.1"
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // core compiles against org.json (the Android platform provides it); desktop ships the artifact.
    implementation("org.json:json:20240303")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.netty:netty-handler:4.1.115.Final")
    implementation("io.netty:netty-codec-http2:4.1.115.Final")
    implementation("io.netty:netty-transport:4.1.115.Final")
    implementation("io.netty:netty-handler-proxy:4.1.115.Final")

    // Windows DPAPI for the stored sign-in session.
    implementation("net.java.dev.jna:jna-platform:5.14.0")
}

compose.desktop {
    application {
        mainClass = "ember.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Ember"
            packageVersion = "1.0.0"
        }
    }
}
