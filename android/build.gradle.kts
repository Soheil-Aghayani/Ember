import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val hevSocks5TunnelVersion = "2.17.1"
val hevSocks5TunnelDir = file("src/main/jni")

val fetchHevSocks5Tunnel = tasks.register<Exec>("fetchHevSocks5Tunnel") {
    val marker = File(hevSocks5TunnelDir, "Android.mk")
    outputs.file(marker)
    onlyIf { !marker.exists() }
    doFirst { hevSocks5TunnelDir.mkdirs() }
    commandLine(
        "git", "clone",
        "--branch", hevSocks5TunnelVersion,
        "--depth", "1",
        "--recursive",
        "--shallow-submodules",
        "https://github.com/heiher/hev-socks5-tunnel.git",
        hevSocks5TunnelDir.absolutePath,
    )
}

tasks.matching { it.name.startsWith("externalNativeBuild") || it.name.contains("NdkBuild") }
    .configureEach { dependsOn(fetchHevSocks5Tunnel) }
tasks.named("preBuild").configure { dependsOn(fetchHevSocks5Tunnel) }

// Xray-core for Android: the official 64-bit ARM build, checked against a pinned SHA-256, packaged as libxray.so.
// Testing on the Android emulator (x86): `gradle -Pemulator :android:assembleDebug` adds an x86_64 build with the Linux
// Xray. The emulator's ARM translation cannot run Xray (a Go program), so this is the only way to try the whole VPN there.
// It never goes into a release build.
val forEmulator = project.hasProperty("emulator")
val tvPreview = project.hasProperty("tvPreview")
val useFlutterUi = !project.hasProperty("composeUi")
val emulatorLibsDir = layout.buildDirectory.dir("emulator-libs").get().asFile
val xrayCoreVersion = "26.11.9"
val xrayEmulatorZipSha256 = "b651f7e1d9966ee2c1b1ee1bf8b52c72771824d3065dce96bbc8bcc19ed95f82"
val xrayAndroidZipSha256 = "edcefc25b9b3195b11e18624c8269a969d6aabc39c6298a9a5ab29de15d46f3b"
val fetchXrayEmulator = tasks.register("fetchXrayEmulator") {
    val target = File(emulatorLibsDir, "x86_64/libxray.so")
    val marker = layout.buildDirectory.file("xray-android/emulator-xray-version.txt").get().asFile
    val markerValue = "$xrayCoreVersion:$xrayEmulatorZipSha256"
    outputs.files(target, marker)
    inputs.property("xrayAsset", markerValue)
    onlyIf { forEmulator && (!target.exists() || !marker.exists() || marker.readText().trim() != markerValue) }
    doLast {
        val zip = layout.buildDirectory.file("xray-android/Xray-linux-64.zip").get().asFile.apply { parentFile.mkdirs() }
        URI("https://github.com/XTLS/Xray-core/releases/download/v$xrayCoreVersion/Xray-linux-64.zip").toURL().openStream()
            .use { input -> zip.outputStream().use { input.copyTo(it) } }
        val actual = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == xrayEmulatorZipSha256) { "Checksum mismatch for the Xray download: $actual" }
        copy {
            from(zipTree(zip)) { include("xray") }
            into(File(emulatorLibsDir, "x86_64"))
            rename("xray", "libxray.so")
        }
        marker.parentFile.mkdirs()
        marker.writeText(markerValue)
    }
}
if (forEmulator) tasks.named("preBuild").configure { dependsOn(fetchXrayEmulator) }

val xrayAndroidDir = file("src/main/jniLibs/arm64-v8a")
val fetchXrayAndroid = tasks.register("fetchXrayAndroid") {
    val target = File(xrayAndroidDir, "libxray.so")
    val marker = layout.buildDirectory.file("xray-android/android-arm64-xray-version.txt").get().asFile
    val markerValue = "$xrayCoreVersion:$xrayAndroidZipSha256"
    outputs.files(target, marker)
    inputs.property("xrayAsset", markerValue)
    onlyIf { !target.exists() || !marker.exists() || marker.readText().trim() != markerValue }
    doLast {
        val zip = layout.buildDirectory.file("xray-android/Xray-android-arm64-v8a.zip").get().asFile.apply { parentFile.mkdirs() }
        URI("https://github.com/XTLS/Xray-core/releases/download/v$xrayCoreVersion/Xray-android-arm64-v8a.zip").toURL().openStream()
            .use { input -> zip.outputStream().use { input.copyTo(it) } }
        val actual = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == xrayAndroidZipSha256) { "Checksum mismatch for the Xray download: $actual" }
        xrayAndroidDir.mkdirs()
        copy {
            from(zipTree(zip)) { include("xray") }
            into(xrayAndroidDir)
            rename("xray", "libxray.so")
        }
        marker.parentFile.mkdirs()
        marker.writeText(markerValue)
    }
}
tasks.named("preBuild").configure { dependsOn(fetchXrayAndroid) }

val releaseKeystoreFile = System.getenv("SIGNING_KEYSTORE")?.let(::File) ?: rootProject.file("release-keystore.jks")
val releaseKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
val releaseStorePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")
val hasReleaseSigning = releaseKeystoreFile.exists() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank()

android {
    namespace = "com.soheil.ember"
    compileSdk = 36

    androidResources {
        // Keep Flutter's large debug kernel intact during Android asset extraction.
        noCompress += "bin"
    }

    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.soheil.ember"
        manifestPlaceholders["launcherActivity"] = if (useFlutterUi) ".MainActivity" else ".ComposeMainActivity"
        manifestPlaceholders["flutterUiEnabled"] = useFlutterUi.toString()
        manifestPlaceholders["launcherAliasEnabled"] = (!useFlutterUi).toString()
        minSdk = 26
        targetSdk = 35
        versionCode = 177
        versionName = "2.7.7"

        externalNativeBuild {
            ndkBuild {
                // Xray is packaged for 64-bit ARM phones (nearly all phones and Android TVs made in the last years)
                abiFilters += if (project.hasProperty("emulator")) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a")
                arguments += listOf(

                    "APP_PLATFORM=android-26",

                    "APP_CFLAGS=-DPKGNAME=com/soheil/ember/vpn/tun -DCLSNAME=HevSocks5Tunnel",
                )
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    sourceSets {
        if (forEmulator) getByName("debug").jniLibs.directories.add(emulatorLibsDir.absolutePath)
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "TV_PREVIEW", tvPreview.toString())
            if (tvPreview) applicationIdSuffix = ".tvpreview"
        }
        release {
            buildConfigField("boolean", "TV_PREVIEW", "false")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources.excludes.add("META-INF/INDEX.LIST")
        resources.excludes.add("META-INF/*.SF")
        resources.excludes.add("META-INF/*.DSA")
        resources.excludes.add("META-INF/*.RSA")
        resources.pickFirsts.add("META-INF/io.netty.versions.properties")
        jniLibs {
            // libxray.so is a program, not a library: it must be unpacked on install and left as it is
            useLegacyPackaging = true
            keepDebugSymbols += "**/libxray.so"
            // Xray is only packaged for 64-bit ARM, so other processors' copies of the support libraries are left out
            if (!forEmulator) excludes += listOf("**/armeabi-v7a/**", "**/x86/**", "**/x86_64/**")
        }
    }
}

dependencies {
    implementation(project(":flutter"))

    // the older Firefox-account code in core is not used here; its network libraries would only add size
    implementation(project(":core")) { exclude(group = "io.netty") }

    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.4.0-alpha10")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")


    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
