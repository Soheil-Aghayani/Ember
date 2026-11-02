<div align="center">

<img src="design/brand/ember-logo.png" alt="Ember logo" width="140"/>

# Ember

**A free, open-source VPN client for Windows that connects through the servers of your own subscription link, tests them and picks the fastest.**

![Windows](https://img.shields.io/badge/Windows-212121?style=for-the-badge&logo=windows&logoColor=white)
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
| Windows PC | Current target | Windows 10/11, 64-bit. Whole-device mode is experimental. |
| Android phone and Android TV | Not converted yet | The Android app in this repository still belongs to an earlier version of the project and does not use subscription links. |

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

Ember is built on the developer's own computer; installers are kept in a local releases folder (see "Build" below).

- **Windows:** `Ember-<version>.exe` (Windows 10/11, 64-bit). Windows may warn about an unknown publisher because the installer is not signed yet: choose **More info**, then **Run anyway**.
- **Android:** a debug APK built with the Android SDK (Android 8.0 or newer).
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
| `desktop/` | Windows desktop app: Kotlin/Compose UI, resources, and installer packaging |
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

- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel): tun2socks engine
- [Wintun](https://www.wintun.net/): Windows virtual network adapter
- [Xray-core](https://github.com/XTLS/Xray-core) (MPL 2.0): runs the servers of a subscription link on Windows; downloaded at build time and checked against a pinned SHA-256
- [Conscrypt](https://github.com/google/conscrypt): TLS with ALPN on Android
- [Solar icon set](https://www.figma.com/community/file/1166831539721848736) by 480 Design (CC BY 4.0): icons
- [circle-flags](https://github.com/HatScripts/circle-flags) (MIT): country flags
- [Inter](https://rsms.me/inter/) (SIL OFL 1.1): typeface
