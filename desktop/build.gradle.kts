import java.net.URL
import java.security.MessageDigest
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

// Whole-device mode on Windows needs a tun2socks engine and the Wintun driver library. They are downloaded
// at build time from their official release pages and verified against pinned SHA-256 checksums.
data class TunnelAsset(val url: String, val sha256: String, val fileName: String)

val tunnelAssets = listOf(
    TunnelAsset(
        "https://github.com/heiher/hev-socks5-tunnel/releases/download/2.19.1/hev-socks5-tunnel-win64.zip",
        "1cc2bd4cc4d47a5e553d4afe9d2f1d40c777016e9f5a48a80177dc69c6c6b523",
        "hev-socks5-tunnel-win64.zip",
    ),
    TunnelAsset(
        "https://www.wintun.net/builds/wintun-0.14.1.zip",
        "07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51",
        "wintun-0.14.1.zip",
    ),
)

val tunnelDir = layout.projectDirectory.dir("appResources/windows-x64/tunnel").asFile

val fetchTunnelBinaries = tasks.register("fetchTunnelBinaries") {
    val marker = File(tunnelDir, ".fetched")
    outputs.file(marker)
    onlyIf { !marker.exists() }
    doLast {
        tunnelDir.mkdirs()
        val downloads = layout.buildDirectory.dir("tunnel-downloads").get().asFile.apply { mkdirs() }
        for (asset in tunnelAssets) {
            val zip = File(downloads, asset.fileName)
            URL(asset.url).openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
            val actual = MessageDigest.getInstance("SHA-256").digest(zip.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(actual == asset.sha256) { "Checksum mismatch for ${asset.fileName}: expected ${asset.sha256}, got $actual" }
            copy {
                from(zipTree(zip))
                into(File(tunnelDir, asset.fileName.removeSuffix(".zip")))
            }
        }
        // hev loads wintun.dll from its own folder.
        val hevExe = tunnelDir.walkTopDown().first { it.name == "hev-socks5-tunnel.exe" }
        val wintun = tunnelDir.walkTopDown().first { it.name == "wintun.dll" && it.parentFile.name == "amd64" }
        wintun.copyTo(File(hevExe.parentFile, "wintun.dll"), overwrite = true)
        marker.writeText("ok")
    }
}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows")
if (isWindowsHost) {
    tasks.matching { it.name == "run" || it.name.startsWith("package") || it.name == "createDistributable" || it.name == "prepareAppResources" }
        .configureEach { dependsOn(fetchTunnelBinaries) }
}

compose.desktop {
    application {
        mainClass = "ember.MainKt"
        nativeDistributions {
            appResourcesRootDir.set(layout.projectDirectory.dir("appResources"))
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Ember"
            packageVersion = System.getenv("EMBER_VERSION") ?: "1.0.0"
            windows {
                menuGroup = "Ember"
                shortcut = true
                dirChooser = true
            }
        }
    }
}
