# Ember project map

This page is the maintained index for the repository. It labels code by responsibility and records platform support separately from future targets.

## Product targets

| Target | Status | Current implementation | Evidence required before calling it supported |
|---|---|---|---|
| Windows PC | Native Rust app 2.0.3 portable release; public code-signing trust unavailable | `ember-rs/` plus `helper/` and its packaged runtime resources. The earlier Kotlin app remains in `desktop/`. | Rust tests and release compilation passed; no installer or live tunnel test. Whole-device mode remains experimental. |
| Android phone | Android 2.7.4 signed arm64 release; physical-device validation pending | `flutter/` UI + `android/` native bridge, engine, and VPN service, with subscription logic in `core/`. | Emulator install and UI navigation checked; verify server import/QR, VPN permissions, connect/disconnect, and traffic on a physical phone. |
| Sony Smart TV | Android 2.7.4 shared APK; forced Flutter TV preview and D-pad navigation checked on API 34 AVD; physical-device validation pending | Shared Flutter UI adapts for Leanback; Kotlin owns the VPN service. This AVD lacks `FEATURE_LEANBACK`, so release auto-detection was not verified. Target: Sony BRAVIA BF1, Android TV OS 12. | Verify launcher visibility, automatic TV layout, physical remote, and VPN behavior on the BRAVIA BF1. |

Sony uses both Android-based TVs and other TV operating systems. The identified target is Sony BRAVIA BF1 with Android TV OS 12. The API 34 emulator check verifies Flutter rendering and D-pad navigation; the owner still needs to verify launcher visibility, physical remote input and VPN behavior on the BRAVIA BF1. See [`ANDROID_TV_SUPPORT.md`](ANDROID_TV_SUPPORT.md) for the device profile and acceptance checklist. References: [Sony's model/OS identification guide](https://www.sony.com/electronics/support/audio-video/articles/00200248), [Android TV navigation](https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv), and [Android TV controller requirements](https://developer.android.com/training/tv/get-started/controllers).

## Architecture categories

| Category | Location | Owns | Boundary |
|---|---|---|---|
| Android client | `flutter/`, `android/` | Flutter phone/TV screens and Android lifecycle, permissions, VPN service, app resources, and packaging. `android/MainActivity.kt` owns Flutter method/event channels; the VPN engine remains native. | Keep Flutter UI in `flutter/` and Android APIs in `android/`. TV screens branch on Leanback and must remain D-pad operable. |
| Windows clients | `ember-rs/`, `desktop/` | Current Rust/Slint preview and earlier Kotlin/Compose app, including Windows resources and installer packaging. | Do not execute the installer or network-changing helper. |
| Shared code | `core/` | Logging, connection check, tunnel config; and the older Android client's Firefox account flows, Mozilla proxy pass, HTTP/2 tunnel and local proxies. | JVM only; no Android or Compose dependencies. |
| Windows native support | `helper/`, `desktop/appResources/windows-x64/` | Native helper sources and packaged Windows tunnel resources. | Do not edit or execute the elevated PowerShell tunnel helper unless the task explicitly requires source changes; never run network-altering code. |
| Design system | `design/` | Brand, tokens, UI assets, and asset attribution. | `design/tokens.json` is the visual source of truth. |
| Repository tooling | `tools/`, root Gradle files, `.github/` | Asset utilities, local multi-target candidate packaging, build orchestration, and repository metadata. | Keep generated outputs out of source changes; do not run GitHub Actions. |
| Documentation | `README.md`, `AGENTS.md`, `docs/`, module READMEs | User-facing overview, agent constraints, architecture map, and module-specific instructions. | Keep platform status consistent in all three top-level guides. |

## Where changes belong

- Shared protocol, session-transfer crypto, and account logic belong in `core/` only when they need no platform APIs.
- Login and session-transfer screens belong in each platform UI (`android/` or `desktop/`); keep shared behavior in `core/` instead of duplicating it.
- Android phone/TV screens, navigation, and responsive layout belong in `flutter/`.
- Android camera scanning, permissions, TV declarations, Flutter channels, and lifecycle code belong in `android/`.
- Android TV launcher declarations belong in `android/`; focus treatment and remote-first screens belong in `flutter/`; Sony device details belong in `docs/ANDROID_TV_SUPPORT.md`.
- Current Windows tray, installer, UI settings, and native integration belong in `ember-rs/` or `helper/`; the earlier Kotlin desktop app remains in `desktop/`.
- Brand-wide colors, spacing, typography, and icons belong in `design/` and its platform mirrors.

## Android sign-in flows (older client)

The Windows app has no sign-in: it runs on subscription links (`desktop/src/main/kotlin/ember/subs/`). The flows below exist only in the Android app.

| Flow | Code owner | Status note |
|---|---|---|
| Email/password and email confirmation | `core/.../data/FxaAuthRepository.kt`; platform login screens | Existing sign-in path; password login can be rejected by a network security challenge. Do not attempt to bypass that challenge. |
| Browser sign-in with PKCE | `core/.../data/BrowserSignIn.kt`, platform login screens | Does not work: Mozilla's Firefox-account OAuth rejects Ember's redirect (`Incorrect redirect_uri`), and Mozilla VPN's own login only serves paying subscribers. |
| One-time sign-in code | `core/.../data/SessionTransfer.kt`, platform session-transfer UI | Encrypted refresh-token transfer with a short expiry; test only with generated fake tokens in local JVM checks. |
| QR transfer | Desktop QR generation and Android ZXing scanner | QR contains the one-time code and passphrase together. Verify the scanner on a camera-equipped Android device; never use the owner's real credentials during testing. |

## Generated areas

`.gradle/`, `.kotlin/`, and module `build/` directories are generated Gradle/Kotlin caches and build outputs. Do not hand-edit or include generated files in feature commits. Build output is evidence of compilation, not proof of real Windows networking, a camera scan, browser sign-in, or Sony TV compatibility.

## Documentation index

- [`README.md`](../README.md) — product overview, target devices, usage, and build entry points.
- [`AGENTS.md`](../AGENTS.md) — required coding, safety, and local build instructions.
- [`desktop/README.md`](../desktop/README.md), [`desktop/TUNNEL.md`](../desktop/TUNNEL.md), [`desktop/SIGNING.md`](../desktop/SIGNING.md) — Windows-specific behavior and packaging.
- [`helper/README.md`](../helper/README.md) — native helper details.
- [`ANDROID_TV_SUPPORT.md`](ANDROID_TV_SUPPORT.md) — Sony BRAVIA BF1 target profile, TV behavior, and validation status.
- [`../flutter/README.md`](../flutter/README.md) — Flutter module architecture, build commands, and native bridge boundary.
- [`WINDOWS_APP_SPLIT_TUNNELING.md`](WINDOWS_APP_SPLIT_TUNNELING.md) — feasibility, the Xray tunnel option, and safety requirements for Windows per-app routing.
- [`design/README.md`](../design/README.md) — design assets and attributions.
- [`history/2026-10-29-ember-sign-in.md`](history/2026-10-29-ember-sign-in.md) — archived sign-in task brief and completion record.

## Connection diagnostics

On Windows the log records the server check before the tunnel starts, the helper steps with timings, and a connection check through Xray alone and through the tunnel; the helper writes `tunnel-state/helper.log` and `hev.log`, and `xray/xray.log` is detailed only when "Detailed log" is on. Android and Windows (older client) log monotonic durations for server selection, account/Guardian proxy-pass acquisition, upstream HTTP/2 setup, and total connect success or failure time. Android also records edge DNS and TUN setup durations; Windows records edge IPv4 resolution in whole-device mode. These stage labels let exported logs distinguish a slow control-plane request, DNS lookup, tunnel handshake, or retry without logging account credentials or tokens.

Android edge DNS answers remain cached for the resolver's five-minute TTL and are invalidated when an underlying network becomes available or is lost. Android control-plane requests try the configured bootstrap DNS-over-HTTPS resolver only after system DNS returns `UnknownHostException`; choosing System DNS disables that fallback.
