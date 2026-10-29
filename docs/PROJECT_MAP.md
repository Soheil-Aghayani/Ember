# Ember project map

This page is the maintained index for the repository. It labels code by responsibility and records platform support separately from future targets.

## Product targets

| Target | Status | Current implementation | Evidence required before calling it supported |
|---|---|---|---|
| Windows PC | Current | `desktop/` plus shared `core/`; Windows tunnel support also uses `helper/` and files under `desktop/appResources/windows-x64/`. | Build the app and installer; the owner validates Windows UI and any real network behavior. Never run the tunnel helper or installer as an agent. |
| Android phone | Current | `android/` plus shared `core/`; phone-first Compose interface and Android VPN service. | Build and install on an emulator or phone; separately verify camera/QR behavior on a device with a camera. |
| Sony Smart TV | Planned; compatibility unknown | No TV-specific source set, launcher, layout, or package is present. | Identify the exact TV model and OS. Then choose Android/Google TV or a separate platform route, add remote-first navigation and packaging, and test on the TV. |

Sony uses both Android-based TVs and other TV operating systems. The model is the compatibility key; a phone APK or phone emulator build does not prove TV support. Android TV apps must work with a remote's D-pad, select, Back, and Home controls. References: [Sony's model/OS identification guide](https://www.sony.com/electronics/support/audio-video/articles/00200248), [Android TV navigation](https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv), and [Android TV controller requirements](https://developer.android.com/training/tv/get-started/controllers).

## Architecture categories

| Category | Location | Owns | Boundary |
|---|---|---|---|
| Android client | `android/` | Phone UI, Android lifecycle, permissions, VPN service, app resources, Android packaging. | Android APIs stay here. A TV client needs an explicit TV experience and compatibility checks; do not silently reuse the phone UI. |
| Windows client | `desktop/` | Desktop UI, Windows-specific resources, distributable and installer packaging. | Do not execute the installer or network-changing helper. |
| Shared engine | `core/` | Firefox account/session flows, Mozilla proxy pass, server list, HTTP/2 tunnel, local proxies. | JVM only; no Android or Compose dependencies. |
| Windows native support | `helper/`, `desktop/appResources/windows-x64/` | Native helper sources and packaged Windows tunnel resources. | Do not edit or execute the elevated PowerShell tunnel helper unless the task explicitly requires source changes; never run network-altering code. |
| Design system | `design/` | Brand, tokens, UI assets, and asset attribution. | `design/tokens.json` is the visual source of truth. |
| Repository tooling | `tools/`, root Gradle files, `.github/` | Asset utilities, build orchestration, and repository metadata. | Keep generated outputs out of source changes; do not run GitHub Actions. |
| Documentation | `README.md`, `AGENTS.md`, `docs/`, module READMEs | User-facing overview, agent constraints, architecture map, and module-specific instructions. | Keep platform status consistent in all three top-level guides. |

## Where changes belong

- Shared protocol, session-transfer crypto, and account logic belong in `core/` only when they need no platform APIs.
- Login and session-transfer screens belong in each platform UI (`android/` or `desktop/`); keep shared behavior in `core/` instead of duplicating it.
- Android camera scanning, permissions, TV declarations, and Android lifecycle code belong in `android/`.
- Windows tray, installer, native helper integration, and Windows settings belong in `desktop/` or `helper/`, according to ownership in `AGENTS.md`.
- Brand-wide colors, spacing, typography, and icons belong in `design/` and its platform mirrors.

## Sign-in flow labels

| Flow | Code owner | Status note |
|---|---|---|
| Email/password and email confirmation | `core/.../data/FxaAuthRepository.kt`; platform login screens | Existing sign-in path; password login can be rejected by a network security challenge. Do not attempt to bypass that challenge. |
| Browser sign-in with PKCE | `core/.../data/BrowserSignIn.kt`, `FxaAuthRepository.kt` | Implemented in the current work; Firefox Account acceptance of the loopback redirect needs a real owner sign-in. |
| One-time sign-in code | `core/.../data/SessionTransfer.kt`, platform session-transfer UI | Encrypted refresh-token transfer with a short expiry; test only with generated fake tokens in local JVM checks. |
| QR transfer | Desktop QR generation and Android ZXing scanner | QR contains the one-time code and passphrase together. Verify the scanner on a camera-equipped Android device; never use the owner's real credentials during testing. |

## Generated areas

`.gradle/`, `.kotlin/`, and module `build/` directories are generated Gradle/Kotlin caches and build outputs. Do not hand-edit or include generated files in feature commits. Build output is evidence of compilation, not proof of real Windows networking, a camera scan, browser sign-in, or Sony TV compatibility.

## Documentation index

- [`README.md`](../README.md) — product overview, target devices, usage, and build entry points.
- [`AGENTS.md`](../AGENTS.md) — required coding, safety, and local build instructions.
- [`desktop/README.md`](../desktop/README.md), [`desktop/TUNNEL.md`](../desktop/TUNNEL.md), [`desktop/SIGNING.md`](../desktop/SIGNING.md) — Windows-specific behavior and packaging.
- [`helper/README.md`](../helper/README.md) — native helper details.
- [`design/README.md`](../design/README.md) — design assets and attributions.
- [`history/2026-10-29-ember-sign-in.md`](history/2026-10-29-ember-sign-in.md) — archived sign-in task brief and completion record.
