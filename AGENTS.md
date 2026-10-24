# Ember: instructions for coding agents

Ember is a free, open-source VPN client (MIT) that signs in with a Firefox account and tunnels traffic through Mozilla's VPN edge. It is a fork of FoxyVPN with a new design. Read this file before changing anything.

## Layout

| Folder | What it is |
|---|---|
| `android/` | Android app (Kotlin, Jetpack Compose, Material 3). Package `com.soheil.ember`. |
| `core/` | JVM-only engine shared by Android and desktop: Firefox sign-in, Mozilla proxy pass, server list, HTTP/2 tunnel, local SOCKS5/HTTP proxies. No Android APIs allowed here. |
| `desktop/` | Compose Desktop app, Windows first. `desktop/appResources/windows-x64/ember-tunnel.ps1` is the elevated tunnel helper. |
| `design/` | Design tokens (`tokens.json`), brand assets, store graphics. |

## Rules

1. **Never push to `main`.** Work on a branch named `agent/<short-task>` and open a pull request. A human or Claude reviews and merges.
2. **There is no local build.** Gradle and the Android SDK are not installed. CI is the check: run the `Android CI` workflow on your branch (`gh workflow run android-ci.yml --ref <branch>`) and make sure the `build`, `desktop` and `desktop-windows` jobs pass.
3. **Do not change these without being asked:** `desktop/appResources/windows-x64/ember-tunnel.ps1` (it changes routes and firewall rules on a real machine), anything under `core/src/main/kotlin/com/soheil/ember/vpn/`, `core/.../data/FxaAuthRepository.kt`, the signing/CI release steps, and `LICENSE`.
4. **Never run network-altering scripts** (`ember-tunnel.ps1`, `route`, `netsh`, firewall changes) on the developer's machine.
5. **No secrets.** Do not commit tokens, passwords, keystores or personal data. Do not print credentials in logs.
6. **Licences.** Apple's SF fonts and SF Symbols must not ship in the Android or Windows apps. Use Inter (font), the Solar icon set and circle-flags (both from `D:\Personal\SVG Icon`, copy only what is used). Keep third-party attributions current in `design/README.md`.
7. **Branding.** No Firefox logo or look-alike fox mark. The mark is the Ember logo in `design/brand/`.
8. **Match the surrounding code.** Same naming, comment density and idiom. Keep changes small and focused.

## Design system

Source of truth: `design/tokens.json` (colours, Inter type scale from Apple's HIG, spacing, radii). Android mirrors it in `android/.../ui/theme/`, desktop in `desktop/.../ui/Theme.kt`. Do not hard-code colours or sizes in screens. Cards are white on a warm cream background with a 1dp outline and 16dp corners; lists are inset-grouped.

## Versions and releases

Desktop installer versions are `1.0.<CI run number>` (set from CI). Manual CI runs on a branch build the Windows installer and attach it to the draft release `desktop-test`. The Android debug APK is the `ember-debug-apk` artifact.

## Good tasks for an agent

- Convert Solar SVG icons or circle-flags into Android vector drawables and use them in the Android screens.
- Copy and wording improvements for UI text and error messages.
- Pure-logic code with a small interface, and its unit tests.
- Reviewing pull requests.

## Hand back to a human when

- A change needs a real Windows or Android device to verify.
- You would touch a file in rule 3.
- CI fails and the cause is not in your change.
