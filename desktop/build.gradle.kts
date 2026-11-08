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
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(kotlin("test"))

    testImplementation("junit:junit:4.13.2")
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
    // Runs the VLESS / VMess / Trojan / Shadowsocks servers of the user's subscription links.
    TunnelAsset(
        "https://github.com/XTLS/Xray-core/releases/download/v26.10.31/Xray-windows-64.zip",
        "2e04a9995a6f2ad1b28c0bb25c708ecb2695e43a1c9c7889c4b6ac2b48cf7a6a",
        "Xray-windows-64.zip",
    ),
    // Decides per program whether traffic uses the VPN (the Apps setting).
    TunnelAsset(
        "https://github.com/SagerNet/sing-box/releases/download/v1.14.4/sing-box-1.14.4-windows-amd64.zip",
        "99185d75d314a07dbba4a130c8ad986cbeb629a07927975636b341ca61f991df",
        "sing-box-1.14.4-windows-amd64.zip",
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
        // Ember does not use Xray's routing data files; leaving them out saves about 10 MB.
        tunnelDir.walkTopDown().filter { it.name == "geoip.dat" || it.name == "geosite.dat" }.forEach { it.delete() }
        val wintun = tunnelDir.walkTopDown().first { it.name == "wintun.dll" && it.parentFile.name == "amd64" }
        wintun.copyTo(File(hevExe.parentFile, "wintun.dll"), overwrite = true)
        marker.writeText("ok")
    }
}

// The native tunnel helper (Rust, ../helper) replaces the slow PowerShell helper. If cargo is not installed the
// build continues and the app falls back to the PowerShell helper.
val buildNativeHelper = tasks.register<Exec>("buildNativeHelper") {
    workingDir = file("../helper")
    commandLine("cargo", "build", "--release")
    onlyIf {
        runCatching { ProcessBuilder("cargo", "--version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
    }
    doLast {
        copy {
            from(file("../helper/target/release/ember-helper.exe"))
            into(layout.projectDirectory.dir("appResources/windows-x64"))
        }
    }
}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows")
if (isWindowsHost) {
    tasks.matching { it.name == "run" || it.name.startsWith("package") || it.name == "createDistributable" || it.name == "prepareAppResources" }
        .configureEach { dependsOn(fetchTunnelBinaries, buildNativeHelper) }
}

tasks.withType<Test> { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "ember.MainKt"
        // The default (G1, heap up to a quarter of the RAM) let Ember grow past 1 GB; this window needs a fraction of that.
        jvmArgs += listOf("-XX:+UseSerialGC", "-Xmx512m", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30")
        nativeDistributions {
            appResourcesRootDir.set(layout.projectDirectory.dir("appResources"))
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Ember"
            packageVersion = System.getenv("EMBER_VERSION") ?: "1.0.0"
            windows {
                iconFile.set(project.file("packaging/ember.ico"))
                menuGroup = "Ember"
                shortcut = true
                dirChooser = true
            }
        }
    }
}
