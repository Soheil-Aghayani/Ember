<div align="center">

<img src="design/brand/ember-logo.png" alt="Ember logo" width="140"/>

# Ember

**A free, open-source VPN client for Windows PCs and Android devices that signs in with your Firefox account. An initial Android TV experience targets the Sony BRAVIA BF1; real-TV validation is pending.**

![Android](https://img.shields.io/badge/Android-212121?style=for-the-badge&logo=android&logoColor=white)
![Windows](https://img.shields.io/badge/Windows-212121?style=for-the-badge&logo=windows&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-212121?style=for-the-badge&logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/License_MIT-212121?style=for-the-badge)

</div>

> [!WARNING]
> **Ember is an early, experimental project.** The Windows whole-device mode changes your routes and firewall rules and has only been tested by its author. Builds are not code-signed yet. Do not rely on it where your safety depends on it.

> [!NOTE]
> Ember is **not affiliated with or endorsed by Mozilla**. It talks to the same service the Firefox browser's built-in VPN uses and runs on the free **50 GB of monthly traffic Mozilla includes with a Firefox account**. When the allowance is used up, connections stop until it resets. Mozilla's terms apply to that service.

<br>

## What it does

Ember signs in with your Firefox account, asks Mozilla's Guardian service for a proxy pass, and carries your traffic to Mozilla's VPN servers over an encrypted HTTP/2 tunnel.

### Target devices

| Device | Status | Notes |
|---|---|---|
| Windows PC | Current target | Windows 10/11, 64-bit. Whole-device mode is experimental. |
| Android phone | Current target | Android 8.0 or newer. |
| Sony Smart TV | Initial Android TV experience implemented; device validation pending | Sony BRAVIA BF1, Android TV OS 12. The Android package has a TV launcher entry and remote-first screens; physical-TV behavior is not yet verified. |

| | Windows | Android |
|---|---|---|
| Sign in with a Firefox account (including email code) | yes | yes |
| Choose a country or city, flags, live exit check | yes | yes |
| Monthly data left | yes | yes |
| Days until the allowance resets | yes | not yet |
| Protect the whole device | yes, experimental | yes |
| Kill switch (block traffic if the tunnel drops) | yes (whole-device mode) | use Android's "Block connections without VPN" setting |
| Proxy-only mode (SOCKS5 and HTTP on your computer) | yes | yes |
| Reconnect after sleep or network change | yes | yes |
| Tray icon, start with Windows, auto-connect | yes | n/a |
| Split tunneling, DNS over HTTPS, upstream proxy | no | yes |
| Addresses that skip the VPN (websites or IP ranges) | yes (whole-device mode) | not yet |
| Pick the fastest server automatically | yes | not yet |
| Speed test through the tunnel | yes | not yet |
| TLS fragmentation, custom MTU | yes | yes |
| English and Persian (right-to-left) | yes (Windows) | not yet |
| Light, dark and system theme | yes | yes |

<br>

## Download

Ember is built on the developer's own computer; installers are kept in a local releases folder (see "Build" below).

- **Windows:** `Ember-<version>.exe` (Windows 10/11, 64-bit). Windows may warn about an unknown publisher because the installer is not signed yet: choose **More info**, then **Run anyway**.
- **Android:** a debug APK built with the Android SDK (Android 8.0 or newer).
- The Android package keeps the phone launcher and adds a Leanback launcher entry for Android TV. TV support is still awaiting validation on the BRAVIA BF1.

See [`desktop/README.md`](desktop/README.md) for how the Windows modes work, the administrator prompt, troubleshooting and how to undo the kill switch.

<br>

## How to use

1. Get a Firefox account (free): [accounts.firefox.com](https://accounts.firefox.com/signup).
2. Install Ember and **sign in** with that account.
3. Pick a location (or keep "Recommended") and press **Connect**.
4. On Windows, turn on **whole-device mode** in Settings to protect every app. Windows asks for administrator permission when you connect.

<br>

## Project layout

| Folder | Contents |
|---|---|
| `android/` | Android phone and TV app: Kotlin, Jetpack Compose, VPN service, platform-aware screens, resources, and Android build configuration |
| `desktop/` | Windows desktop app: Kotlin/Compose UI, resources, and installer packaging |
| `core/` | Platform-neutral JVM logic: Firefox sign-in, session transfer, proxy pass, server list, HTTP/2 tunnel, local proxies |
| `helper/` | Native Rust helper for Windows whole-device tunnel setup |
| `design/` | Design tokens, brand assets, store graphics, and third-party notices |
| `tools/` | Repository utilities, including asset converters |
| `docs/` | Architecture map, platform status, and contributor documentation |
| `.github/` | Repository metadata and workflow definitions |
| Root Gradle files | Shared build configuration and module inclusion |

Generated `.gradle/`, `.kotlin/`, and module `build/` folders are build output/cache areas, not source. Read [`docs/PROJECT_MAP.md`](docs/PROJECT_MAP.md) for ownership boundaries and the Sony TV compatibility gate.

Design: warm neutrals with Firefox orange and violet accents, Inter typeface, Solar icons, round country flags. Source of truth: [`design/tokens.json`](design/tokens.json).

<br>

## Build

Windows (on a Windows PC): JDK 17 or newer, Gradle 8.10, Rust (`cargo`) and [Inno Setup 6](https://jrsoftware.org/isinfo.php).

```powershell
# 1. Build the Windows app folder.
$env:EMBER_VERSION = '1.0.51'
gradle -p desktop createDistributable

# 2. Build the installer from the desktop/ directory.
Set-Location desktop
& 'D:\Programming\tools\InnoSetup\ISCC.exe' /DVersion=1.0.51 `
  "/DSourceDir=$PWD\build\compose\binaries\main\app\Ember" `
  "/DOutputDir=$PWD\build\installer" `
  packaging\ember.iss
```

Android needs Android SDK 35 with NDK `26.1.10909125`:

```bash
gradle :android:assembleDebug
```

The Android debug APK is written to `android/build/outputs/apk/debug/android-debug.apk`.

The helper's unit tests run with `cargo test --manifest-path helper/Cargo.toml`.

<br>

## Contributing

Issues and pull requests are welcome. Coding agents and new contributors should read [`AGENTS.md`](AGENTS.md) first.

<br>

## Credits and licence

Ember is a fork of [FoxyVPN](https://github.com/Vauth/FoxyVPN) by Vauth, redesigned and extended with a shared core, a Windows app and a new look. It is released under the **MIT** licence; see [LICENSE](LICENSE).

- [firefox-vpn-client](https://github.com/UjuiUjuMandan/firefox-vpn-client): the Go reference client the engine is ported from
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel): tun2socks engine
- [Wintun](https://www.wintun.net/): Windows virtual network adapter
- [Xray-core](https://github.com/XTLS/Xray-core) (MPL 2.0): runs the servers of a subscription link on Windows; downloaded at build time and checked against a pinned SHA-256
- [Conscrypt](https://github.com/google/conscrypt): TLS with ALPN on Android
- [Solar icon set](https://www.figma.com/community/file/1166831539721848736) by 480 Design (CC BY 4.0): icons
- [circle-flags](https://github.com/HatScripts/circle-flags) (MIT): country flags
- [Inter](https://rsms.me/inter/) (SIL OFL 1.1): typeface
