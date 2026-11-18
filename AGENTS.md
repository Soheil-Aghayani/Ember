# Ember: instructions for coding agents

Ember is a free, open-source VPN client (MIT) for Windows. The user adds subscription links (VLESS, VMess, Trojan, Shadowsocks, Hysteria2 or Xray JSON) or server files (Xray JSON, WireGuard `.conf`, OpenVPN `.ovpn` run through the OpenVPN the user installed, without Ember's kill switch); Ember reads the servers, measures them, and connects through the chosen one with Xray-core, optionally for the whole computer (Wintun tunnel and kill switch). It began as a fork of FoxyVPN, a Firefox-account client; the Android app has been rewritten for subscription links; the Firefox code left in `core/` is unused. Read this file before changing anything.

## Product targets

| Target | Status | Compatibility rule |
|---|---|---|
| Windows PC | Current native preview in `ember-rs/`; the earlier Kotlin app remains in `desktop/` | Subscription links and Xray; whole-device mode is experimental and must not be run by an agent. |
| Android phone | Converted, not yet tried on a device | The Android app (`android/`) opens subscription links, files, QR codes and WARP like the desktop app and connects with Xray (packaged as `libxray.so`) plus hev-socks5-tunnel, for 64-bit ARM only. It shares the server code in `core/src/main/kotlin/ember/subs/`. It also has the Apps setting (Android's own per-app VPN rules), the website check, automatic failover, a quick-settings tile, a 2x2 home-screen widget (one tap connects or disconnects), a QR code for sharing a server, frosted-glass panels (shared look with the desktop app) and a Disconnect button in its notification. It is in English and Persian (with a language choice in Settings). OpenVPN and the kill switch are desktop-only. To test on the emulator build with `-Pemulator` (x86 Xray; the emulator cannot run the ARM one). |
| Sony Smart TV | Initial Android TV experience implemented; physical-device validation pending | Known target: Sony BRAVIA BF1, Android TV OS 12. Keep TV screens remote-first and do not call TV support verified until the owner checks it on this device. |

Keep this platform status in sync with `README.md` and `docs/PROJECT_MAP.md`. A successful Android build is not evidence of Sony TV runtime support.

## Layout

| Folder | What it is |
|---|---|
| `android/` | Android phone and Android TV app (Kotlin, Jetpack Compose, Material 3). Package `com.soheil.ember`; TV UI is selected from the Leanback device feature. |
| `core/` | JVM-only code. The Windows app uses only its logging, connection-check and tunnel-config helpers; the Firefox sign-in, proxy pass and HTTP/2 tunnel belong to the older Android client. No Android APIs allowed here. |
| `ember-rs/` | Current native Rust/Slint Windows preview, with pure Rust subscription and engine crates plus Inno Setup packaging. It installs side-by-side with the Kotlin app. |
| `desktop/` | Earlier Compose Desktop app for Windows. `desktop/.../subs/` reads subscriptions and runs Xray; `desktop/.../engine/` owns that app's connection; `desktop/appResources/windows-x64/ember-tunnel.ps1` is its fallback elevated tunnel helper. |
| `design/` | Design tokens (`tokens.json`), brand assets, store graphics, and the orb and background pictures (`orb-*.png`, `bg-*.png`, `widget-orb.png`, made in GPT). The apps use converted copies: `android/.../res/drawable-nodpi/*.webp` and `desktop/.../resources/art/*.webp`; the orbs there have real transparency (alpha from brightness). |
| `docs/` | Project map, platform status, and contributor-facing architecture notes. |
| `helper/` | Native Rust helper for Windows whole-device tunnel setup. Do not run it. |
| `tools/` | Repository utilities and asset-conversion scripts. |
| `.github/` | Repository metadata and workflow definitions; local policy forbids running GitHub Actions. |

Root Gradle files coordinate builds. `.gradle/`, `.kotlin/`, and module `build/` folders are generated caches/artifacts, not maintained source.

## Rules

1. **Work locally only.** Do not push to GitHub, run GitHub Actions or publish releases; the project is built and kept on this computer. Work on a branch named `agent/<short-task>` (in your own git worktree if another session is editing the main folder) and commit locally. Do not commit to `main`; a human or Claude reviews and merges.
2. **Build and check locally.** Gradle 8.10 is in `D:\Programming\tools\gradle\gradle-8.10.2\bin` (set `JAVA_HOME` to `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` and add `C:\Users\Soheil\.cargo\bin` to `PATH`). `gradle -p desktop createDistributable` builds the Windows app folder (it also builds `helper/` and `desktop-rust/` with cargo); `D:\Programming\tools\InnoSetup\ISCC.exe` makes the installer from `desktop/packaging/ember.iss`. Run `cargo test` in `helper/` and `gradle -p desktop test` for the parser tests. The Android SDK is in `D:\Programming\tools\android-sdk` (named in the untracked `local.properties`): `gradle :android:assembleDebug` makes a debug-signed APK in `android/build/outputs/apk/debug`. The hev-socks5-tunnel sources in `android/src/main/jni` are cloned on first build; Git on Windows leaves its symlinked headers as one-line text files, which must be replaced with copies of their targets before the native build works. Never run the installer or the network helper yourself; the owner tests them.
3. **Do not change these without being asked:** `desktop/appResources/windows-x64/ember-tunnel.ps1` (it changes routes and firewall rules on a real machine), anything under `core/src/main/kotlin/com/soheil/ember/vpn/`, `core/.../data/FxaAuthRepository.kt`, the signing/CI release steps, and `LICENSE`.
4. **Never run network-altering scripts** (`ember-tunnel.ps1`, `route`, `netsh`, firewall changes) on the developer's machine.
5. **No secrets.** Do not commit tokens, passwords, keystores or personal data. Do not print credentials in logs. A subscription link is a secret (it carries the account's key): never log it (log the host only), never commit one, and never put a real one in a test.
6. **Licences.** Apple's SF fonts and SF Symbols must not ship in the Android or Windows apps. Use Inter (font), the Solar icon set and circle-flags (both from `D:\Personal\SVG Icon`, copy only what is used). Keep third-party attributions current in `design/README.md`.
7. **Branding.** No Firefox logo or look-alike fox mark. The mark is the Ember logo in `design/brand/`.
8. **Match the surrounding code.** Same naming, comment density and idiom. Keep changes small and focused.

## Design system

Source of truth: `design/tokens.json` (colours, Inter type scale from Apple's HIG, spacing, radii). Android mirrors it in `android/.../ui/theme/`, desktop in `desktop/.../ui/Theme.kt`. Do not hard-code colours or sizes in screens. Cards are white on a warm cream background with a 1dp outline and 16dp corners; lists are inset-grouped.

## Versions and releases

`tools/release.ps1 -Version 1.0.<n>` builds, tests, signs (when given a certificate) and copies the installer; see `docs/RELEASING.md`. `tools/build-apk.ps1` builds the signed Android APK (key from `tools/New-AndroidKeystore.ps1`, kept outside the repository). The Android SDK is in `D:\Programming	oolsndroid-sdk`; Google's download hosts refuse some networks, so fetch it through a working server.

Desktop installer versions are set with the `EMBER_VERSION` environment variable (`1.0.<n>`, counting up from the last installer in `C:\Users\Soheil\Downloads\Programs`). Finished installers are copied to that folder, and release notes plus checksums live in `D:\Programming\Ember-releases`.

## Good tasks for an agent

- Convert Solar SVG icons or circle-flags into Android vector drawables and use them in the Android screens.
- Copy and wording improvements for UI text and error messages.
- Pure-logic code with a small interface, and its unit tests.
- Reviewing pull requests.

## Hand back to a human when

- A change needs a real Windows or Android device to verify.
- You would touch a file in rule 3.
- CI fails and the cause is not in your change.
