<div align="center">

<img src="design/brand/ember-logo.png" alt="Ember logo" width="140"/>

# Ember

**A free, open-source VPN client for Windows and Android that connects through the servers in your subscription, tests them and helps you pick one.**

![Windows](https://img.shields.io/badge/Windows-212121?style=for-the-badge&logo=windows&logoColor=white)
![Rust](https://img.shields.io/badge/Rust-212121?style=for-the-badge&logo=rust&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-212121?style=for-the-badge&logo=kotlin&logoColor=white)
![Flutter](https://img.shields.io/badge/Flutter-212121?style=for-the-badge&logo=flutter&logoColor=white)
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
| Windows PC | Current local review build: native Rust app 2.0.2; latest prepared installer: 2.0.1 | Windows 10/11, 64-bit. Whole-device mode is experimental; the preview is packaged side-by-side with the earlier Kotlin app. |
| Android phone | Android 2.7.3 published; physical-device validation pending | Android 8.0 or newer; arm64. The Kotlin VPN engine remains native. The Flutter build has not been checked on a physical phone. |
| Android TV | Android 2.7.3 shared APK; Flutter TV preview checked on API 34 emulator | The emulator check covered Home, Servers, Settings, Logs, What's new and About. Physical Sony BRAVIA BF1 launcher and VPN behavior remain unverified. |

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

The shared app catalogue at `https://app.agseyl.ir/` hosts downloads for multiple apps. The signed Android 2.7.3 arm64 APK is published for phones and Android TV; physical phone and Sony BRAVIA validation remain pending.

- **Windows:** the Rust 2.0.1 preview installer remains the latest prepared installer. A local 2.0.2 review build is available, but it is not packaged or published as a release.
- **Android phone and TV:** the signed arm64 APK, version 2.7.3, is live on the shared catalogue. Physical phone VPN/QR behavior and Sony BRAVIA BF1 launcher/VPN behavior remain unverified.
- The Android package keeps the phone launcher and adds a Leanback launcher entry for Android TV. The latest Flutter TV preview was checked on an API 34 emulator for D-pad navigation across all six pages and updated visible focus treatment. This does not verify the physical TV or VPN traffic.

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
| `android/` | Android phone and TV host: Kotlin Activity bridge, VPN service, platform APIs, resources, and Android build configuration |
| `flutter/` | Flutter phone/TV UI, native-channel client, app assets, and add-to-app module |
| `desktop/` | Earlier Windows desktop app: Kotlin/Compose UI, resources, and installer packaging |
| `ember-rs/` | Current Windows Rust/Slint preview app and installer packaging |
| `core/` | Platform-neutral JVM logic. The Windows app uses only its logging and connection-check helpers; the rest belongs to the older Android version |
| `helper/` | Native Rust helper for Windows whole-device tunnel setup |
| `design/` | Design tokens, brand assets, store graphics, and third-party notices |
| `tools/` | Repository utilities, asset converters, and local release-candidate packaging |
| `docs/` | Architecture map, platform status, and contributor documentation |
| `.github/` | Repository metadata and workflow definitions |
| Root Gradle files | Shared build configuration and module inclusion |

Generated `.gradle/`, `.kotlin/`, and module `build/` folders are build output/cache areas, not source. Read [`docs/PROJECT_MAP.md`](docs/PROJECT_MAP.md) for ownership boundaries and the Sony TV compatibility gate.

Design: warm neutrals with orange and violet accents, Inter typeface, Solar icons, round country flags. Source of truth: [`design/tokens.json`](design/tokens.json); Flutter screen styles are centralized in `flutter/lib/ember_ui.dart`.

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

The Android host now uses Flutter 3.50 with Dart 3.14, Android SDK 36, Gradle 9.5.0, AGP 9.3.1, CMake 3.22.1, and NDK `26.1.10909125`. The Flutter engine also requests NDK `28.2.13676358`. The Gradle wrapper pins version 9.5.0 and verifies the official distribution checksum.

Fetch Flutter dependencies and build the phone APK:

```powershell
Set-Location flutter
flutter pub get
Set-Location ..
.\gradlew.bat :android:assembleDebug
```

Build the debug-only TV layout with `.\gradlew.bat :android:assembleDebug -PtvPreview`; it uses the `.tvpreview` package suffix. Both builds write `android/build/outputs/apk/debug/android-debug.apk`, so save the first APK before building the other variant. These are local debug packages, not release artifacts.

The signed Android release script uses the root Gradle wrapper and verifies the APK signature. It has not yet been run against the Flutter port; signing requires the Android keystore kept outside the repository. For a local, versioned candidate bundle across PC, phone and TV, see [`docs/RELEASING.md`](docs/RELEASING.md).

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
