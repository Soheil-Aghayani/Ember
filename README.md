<div align="center">

<img src="design/brand/ember-logo.png" alt="Ember logo" width="140"/>

# Ember

**A free, open-source VPN client for Windows and Android that signs in with your Firefox account.**

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

Builds are produced by GitHub Actions. See the [Releases](https://github.com/Soheil-Aghayani/Ember/releases) page for installers, or open a run of the **Android CI** workflow in the Actions tab and download its artifacts:

- **Windows:** `Ember-<version>.exe` (Windows 10/11, 64-bit). Windows may warn about an unknown publisher because the installer is not signed yet: choose **More info**, then **Run anyway**.
- **Android:** `ember-debug-apk` (Android 8.0 or newer).

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
| `android/` | Android app (Kotlin, Jetpack Compose, Material 3) |
| `desktop/` | Windows app (Kotlin, Compose Desktop) and the elevated network helper |
| `core/` | Shared engine: Firefox sign-in, Mozilla proxy pass, server list, HTTP/2 tunnel, local proxies |
| `design/` | Design tokens, brand assets, store graphics, third-party notices |
| `tools/` | Scripts, for example the SVG to Android vector converter |

Design: warm neutrals with Firefox orange and violet accents, Inter typeface, Solar icons, round country flags. Source of truth: [`design/tokens.json`](design/tokens.json).

<br>

## Build

There is no need to install anything locally: the **Android CI** workflow builds everything (Android APK, Windows installer). Start it from the Actions tab (**Run workflow**), or:

```bash
gh workflow run android-ci.yml --ref main
```

To build on your own machine you need JDK 17, Gradle 8.10, Android SDK 35 with NDK `26.1.10909125` (Android) and `git` on `PATH`:

```bash
gradle :android:assembleDebug      # Android
gradle -p desktop packageExe       # Windows installer (run on Windows)
```

Windows installer versions are `1.0.<CI run number>`.

<br>

## Contributing

Issues and pull requests are welcome. Coding agents and new contributors should read [`AGENTS.md`](AGENTS.md) first.

<br>

## Credits and licence

Ember is a fork of [FoxyVPN](https://github.com/Vauth/FoxyVPN) by Vauth, redesigned and extended with a shared core, a Windows app and a new look. It is released under the **MIT** licence; see [LICENSE](LICENSE).

- [firefox-vpn-client](https://github.com/UjuiUjuMandan/firefox-vpn-client): the Go reference client the engine is ported from
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel): tun2socks engine
- [Wintun](https://www.wintun.net/): Windows virtual network adapter
- [Conscrypt](https://github.com/google/conscrypt): TLS with ALPN on Android
- [Solar icon set](https://www.figma.com/community/file/1166831539721848736) by 480 Design (CC BY 4.0): icons
- [circle-flags](https://github.com/HatScripts/circle-flags) (MIT): country flags
- [Inter](https://rsms.me/inter/) (SIL OFL 1.1): typeface
