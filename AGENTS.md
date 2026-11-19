# Ember: instructions for coding agents

Ember is a free, open-source VPN client (MIT) for Windows and Android. The user adds subscription links (VLESS, VMess, Trojan, Shadowsocks, Hysteria2 or Xray JSON) or server files (Xray JSON, WireGuard `.conf`, OpenVPN `.ovpn` run through the OpenVPN the user installed, without Ember's kill switch); Ember reads the servers, measures them, and connects through the chosen one with Xray-core, optionally for the whole computer (Wintun tunnel and kill switch). It began as a fork of FoxyVPN, a Firefox-account client; the Android UI is being ported to Flutter while its Kotlin VPN engine and platform services remain native. The Firefox code left in `core/` is unused by the current subscription client. Read this file before changing anything.

## Product targets

| Target | Status | Compatibility rule |
|---|---|---|
| Windows PC | Current native preview in `ember-rs/`; the earlier Kotlin app remains in `desktop/` | Subscription links and Xray; whole-device mode is experimental and must not be run by an agent. |
| Android phone | Flutter UI port compiles locally; runtime device check pending | Flutter screens are in `flutter/`. The Kotlin bridge, `AndroidEngine`, Xray VPN service, app-routing rules, and Android permissions stay in `android/`. Existing features include subscription links/files, QR import/share, WARP, app routing, site checks, failover, tile/widget, logs, and English/Persian. The debug build targets arm64; OpenVPN and the kill switch are desktop-only. A successful build does not prove VPN or QR behavior on a phone. |
| Sony Smart TV | Flutter TV preview runs in an Android TV API 34 emulator; D-pad navigation across all six pages checked; physical-device validation pending | Known target: Sony BRAVIA BF1, Android TV OS 12. Keep Flutter TV screens remote-first. Emulator checks cover the Flutter UI only; do not call TV support verified until the owner checks launcher, remote, and VPN behavior on this device. |

Keep this platform status in sync with `README.md` and `docs/PROJECT_MAP.md`. A successful Android build is not evidence of Sony TV runtime support.

## Layout

| Folder | What it is |
|---|---|
| `android/` | Android host and platform bridge (Kotlin), VPN service, shared engine, permissions, activity-result APIs, and app resources. `MainActivity` hosts Flutter; `ComposeMainActivity` keeps the old UI available as a rollback build. |
| `flutter/` | Phone and TV UI, screen navigation, theme, Solar icons, Inter fonts, localization, and the Android method/event channel client. Android owns VPN/network state. |
| `core/` | JVM-only code. The Windows app uses only its logging, connection-check and tunnel-config helpers; the Firefox sign-in, proxy pass and HTTP/2 tunnel belong to the older Android client. No Android APIs allowed here. |
| `ember-rs/` | Current native Rust/Slint Windows preview, with pure Rust subscription and engine crates plus Inno Setup packaging. It installs side-by-side with the Kotlin app. |
| `desktop/` | Earlier Compose Desktop app for Windows. `desktop/.../subs/` reads subscriptions and runs Xray; `desktop/.../engine/` owns that app's connection; `desktop/appResources/windows-x64/ember-tunnel.ps1` is its fallback elevated tunnel helper. |
| `design/` | Design tokens (`tokens.json`), brand assets, store graphics, and artwork. Flutter bundles UI assets from `flutter/assets/`; the older Compose and desktop apps use their converted copies. |
| `docs/` | Project map, platform status, and contributor-facing architecture notes. |
| `helper/` | Native Rust helper for Windows whole-device tunnel setup. Do not run it. |
| `tools/` | Repository utilities and asset-conversion scripts. |
| `.github/` | Repository metadata and workflow definitions; local policy forbids running GitHub Actions. |

Root Gradle files coordinate builds. `.gradle/`, `.kotlin/`, and module `build/` folders are generated caches/artifacts, not maintained source.

## Rules

1. **Work locally only.** Do not push to GitHub, run GitHub Actions or publish releases; the project is built and kept on this computer. Work on a branch named `agent/<short-task>` (in your own git worktree if another session is editing the main folder) and commit locally. Do not commit to `main`; a human or Claude reviews and merges.
2. **Build and check locally.** Gradle 8.10 remains for older desktop builds; the Android host uses the root wrapper pinned to Gradle 9.5.0, AGP 9.3.1, and Flutter 3.50 (`D:\Programming\tools\flutter\bin`). Set `JAVA_HOME` to `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`; the Android SDK is `D:\Programming\tools\android-sdk`. The Android build uses SDK 36, host NDK `26.1.10909125`, Flutter NDK `28.2.13676358`, and CMake 3.22.1. Run `flutter pub get` from `flutter/`, then `..\gradlew.bat :android:assembleDebug` for the phone debug APK or add `-PtvPreview` for the TV-preview package. The signed release script was not validated for the Flutter port; review it before preparing a release. The hev-socks5-tunnel sources in `android/src/main/jni` are cloned on first build; Git on Windows leaves its symlinked headers as one-line text files, which must be replaced with copies of their targets before the native build works. Never run the installer or the network helper yourself; the owner tests them.
3. **Do not change these without being asked:** `desktop/appResources/windows-x64/ember-tunnel.ps1` (it changes routes and firewall rules on a real machine), anything under `core/src/main/kotlin/com/soheil/ember/vpn/`, `core/.../data/FxaAuthRepository.kt`, the signing/CI release steps, and `LICENSE`.
4. **Never run network-altering scripts** (`ember-tunnel.ps1`, `route`, `netsh`, firewall changes) on the developer's machine.
5. **No secrets.** Do not commit tokens, passwords, keystores or personal data. Do not print credentials in logs. A subscription link is a secret (it carries the account's key): never log it (log the host only), never commit one, and never put a real one in a test.
6. **Licences.** Apple's SF fonts and SF Symbols must not ship in the Android or Windows apps. Use Inter (font), the Solar icon set and circle-flags (both from `D:\Personal\SVG Icon`, copy only what is used). Keep third-party attributions current in `design/README.md`.
7. **Branding.** No Firefox logo or look-alike fox mark. The mark is the Ember logo in `design/brand/`.
8. **Match the surrounding code.** Same naming, comment density and idiom. Keep changes small and focused.

## Design system

Source of truth: `design/tokens.json` (colours, Inter type scale from Apple's HIG, spacing, radii). Flutter centralizes its theme/widgets in `flutter/lib/ember_ui.dart`; the older Compose and desktop apps have platform mirrors. Do not hard-code repeated colours or sizes in individual screens.

## Versions and releases

`tools/release.ps1 -Version 1.0.<n>` builds, tests, signs (when given a certificate) and copies the installer; see `docs/RELEASING.md`. `tools/build-apk.ps1` builds the signed Android APK (key from `tools/New-AndroidKeystore.ps1`, kept outside the repository). The Android SDK is in `D:\Programming	oolsndroid-sdk`; Google's download hosts refuse some networks, so fetch it through a working server.

Desktop installer versions are set with the `EMBER_VERSION` environment variable (`1.0.<n>`, counting up from the last installer in `C:\Users\Soheil\Downloads\Programs`). Finished installers are copied to that folder, and release notes plus checksums live in `D:\Programming\Ember-releases`.

The existing Android signing script still pins Gradle 8.10.2. Update it to use the root wrapper and validate it before preparing a signed Android release. The Android SDK root is `D:\Programming\tools\android-sdk`.

## Good tasks for an agent

- Convert Solar SVG icons or circle-flags into Android vector drawables and use them in the Android screens.
- Copy and wording improvements for UI text and error messages.
- Pure-logic code with a small interface, and its unit tests.
- Reviewing pull requests.

## Hand back to a human when

- A change needs a real Windows or Android device to verify.
- You would touch a file in rule 3.
- CI fails and the cause is not in your change.
