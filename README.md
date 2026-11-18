<div align="center">

<img src="design/brand/ember-logo.png" alt="Ember logo" width="140"/>

# Ember

**A free, open-source VPN client for Windows and Android that connects through the servers in your subscription, tests them and helps you pick one.**

![Windows](https://img.shields.io/badge/Windows-212121?style=for-the-badge&logo=windows&logoColor=white)
![Rust](https://img.shields.io/badge/Rust-212121?style=for-the-badge&logo=rust&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-212121?style=for-the-badge&logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/License_MIT-212121?style=for-the-badge)

</div>

> [!WARNING]
> **Ember is an early, experimental project.** The Windows whole-device mode changes your routes and firewall rules and has only been tested by its author. Builds are not code-signed yet. Do not rely on it where your safety depends on it.

> [!NOTE]
> Ember does not provide servers. You bring a subscription link from a provider you trust; Ember reads it and connects through its servers. Your provider's terms apply.

<br>

## What it does

You paste a subscription link (`https://…/sub/…`). Ember downloads the server list, shows your data use and days left when the panel reports them, measures the delay and download speed of every server, sorts the list from fastest to slowest and connects through the server you pick. The servers run in [Xray-core](https://github.com/XTLS/Xray-core): VLESS (including REALITY), VMess, Trojan and Shadowsocks links.

### Target devices

| Device | Status | Notes |
|---|---|---|
| Windows PC | Current preview: native Rust app 2.0.1 | Windows 10/11, 64-bit. Whole-device mode is experimental; this preview is packaged side-by-side with the earlier Kotlin app. |
| Android phone | Release APK prepared: 2.7.2 | Android 8.0 or newer; arm64. VPN and QR behavior still need a physical phone check. |
| Android TV | APK prepared; device validation pending | Same arm64 APK includes the TV launcher. Verify remote navigation and VPN behavior on the Sony BRAVIA BF1 before calling TV support verified. |

| Windows | |
|---|---|
| Add subscription links, refresh them, see usage and days left | yes |
| Servers sorted by measured speed, with delay and speed per server | yes |
| Protect the whole device (Wintun adapter) | yes, experimental |
| Kill switch (block traffic if the tunnel drops) | yes |
| Proxy-only mode (SOCKS5 and HTTP on your computer) | yes |
| Reconnect after sleep or network change | yes |
| Connect automatically on networks you do not trust | yes |
| Addresses that skip the VPN (websites or IP ranges) | yes |
| Tray icon, start with Windows, speed test through the tunnel | yes |
| English and Persian (right-to-left), light and dark theme | yes |

<br>

## Download

Release candidates and checksums are prepared in the shared Ember app catalogue at `https://app.agseyl.ir/`; this catalogue is intended to host multiple apps.

- **Windows:** the Rust 2.0.1 preview installer (Windows 10/11, 64-bit). It remains side-by-side with the older Kotlin app; whole-device networking needs owner verification.
- **Android phone and TV:** signed arm64 APK (Android 8.0 or newer). The same APK has both phone and Leanback launchers; physical TV behavior remains unverified.
- The Android package keeps the phone launcher and adds a Leanback launcher entry for Android TV. TV support is still awaiting validation on the BRAVIA BF1.

See [`desktop/README.md`](desktop/README.md) for how the Windows modes work, the administrator prompt, troubleshooting and how to undo the kill switch.

<br>

## How to use

1. Get a subscription link from your VPN provider.
2. Install Ember, open **Servers**, press **Add link** and paste it.
3. Press **Test speed**, click the fastest server and press **Connect** on Home.
4. Whole-device mode is on by default and protects every app. Windows asks for administrator permission when you connect.

<br>

## Project layout

| Folder | Contents |
|---|---|
| `android/` | Android phone and TV app: Kotlin, Jetpack Compose, VPN service, platform-aware screens, resources, and Android build configuration |
| `desktop/` | Earlier Windows desktop app: Kotlin/Compose UI, resources, and installer packaging |
| `ember-rs/` | Current Windows Rust/Slint preview app and installer packaging |
| `core/` | Platform-neutral JVM logic. The Windows app uses only its logging and connection-check helpers; the rest belongs to the older Android version |
| `helper/` | Native Rust helper for Windows whole-device tunnel setup |
| `design/` | Design tokens, brand assets, store graphics, and third-party notices |
| `tools/` | Repository utilities, including asset converters |
| `docs/` | Architecture map, platform status, and contributor documentation |
| `.github/` | Repository metadata and workflow definitions |
| Root Gradle files | Shared build configuration and module inclusion |

Generated `.gradle/`, `.kotlin/`, and module `build/` folders are build output/cache areas, not source. Read [`docs/PROJECT_MAP.md`](docs/PROJECT_MAP.md) for ownership boundaries and the Sony TV compatibility gate.

Design: warm neutrals with orange and violet accents, Inter typeface, Solar icons, round country flags. Source of truth: [`design/tokens.json`](design/tokens.json).

<br>

## Build

Windows (on a Windows PC): Rust (`cargo`) and [Inno Setup 6](https://jrsoftware.org/isinfo.php). The current native preview is built and staged separately from the older Kotlin desktop app.

```powershell
# Build and test the current native Windows app and helper.
Set-Location ember-rs
cargo test --workspace
cargo build --release -p ember-ui
cargo build --release --manifest-path ..\helper\Cargo.toml

# Stage with the resources from an existing Ember install, then compile the installer.
powershell -File installer\stage.ps1 -Resources 'C:\Program Files\Ember\app\resources' -Out .\stage
& 'D:\Programming\tools\InnoSetup\ISCC.exe' /DVersion=2.0.1 `
  "/DSourceDir=$PWD\stage" "/DOutputDir=$PWD\Output" installer\ember-rs.iss
```

Android needs Android SDK 35 with NDK `26.1.10909125`. A signed arm64 release APK can be built with:

```bash
tools/build-apk.ps1
```

The Android debug APK is written to `android/build/outputs/apk/debug/android-debug.apk`. Android unit tests are currently absent; Gradle's `:android:testDebugUnitTest` task therefore has no sources.

The helper's unit tests run with `cargo test --manifest-path helper/Cargo.toml`.

<br>

## Contributing

Issues and pull requests are welcome. Coding agents and new contributors should read [`AGENTS.md`](AGENTS.md) first.

<br>

## Credits and licence

Ember is a fork of [FoxyVPN](https://github.com/Vauth/FoxyVPN) by Vauth, redesigned and extended with a shared core, a Windows app and a new look. It is released under the **MIT** licence; see [LICENSE](LICENSE).

- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel): tun2socks engine
- [Wintun](https://www.wintun.net/): Windows virtual network adapter
- [Xray-core](https://github.com/XTLS/Xray-core) (MPL 2.0): runs the servers of a subscription link on Windows; downloaded at build time and checked against a pinned SHA-256
- [sing-box](https://github.com/SagerNet/sing-box) (GPL-3.0-or-later): optional per-app routing in the Windows build; [v1.14.4 source](https://github.com/SagerNet/sing-box/releases/tag/v1.14.4)
- [Conscrypt](https://github.com/google/conscrypt): TLS with ALPN on Android
- [Solar icon set](https://www.figma.com/community/file/1166831539721848736) by 480 Design (CC BY 4.0): icons
- [circle-flags](https://github.com/HatScripts/circle-flags) (MIT): country flags
- [Inter](https://rsms.me/inter/) (SIL OFL 1.1): typeface
