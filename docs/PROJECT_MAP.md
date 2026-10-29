# Ember project map

This page is the maintained index for the repository. It labels code by responsibility and records platform support separately from future targets.

## Product targets

| Target | Status | Current implementation | Evidence required before calling it supported |
|---|---|---|---|
| Windows PC | Current | `desktop/` plus shared `core/`; Windows tunnel support also uses `helper/` and files under `desktop/appResources/windows-x64/`. | Build the app and installer; the owner validates Windows UI and any real network behavior. Never run the tunnel helper or installer as an agent. |
| Android phone | Current | `android/` plus shared `core/`; phone-first Compose interface and Android VPN service. | Build and install on an emulator or phone; separately verify camera/QR behavior on a device with a camera. |
| Sony Smart TV | Initial Android TV experience implemented; device validation pending | Same `android/` package with an optional Leanback launcher, remote-first home/login flow, and shared VPN service. Target: Sony BRAVIA BF1, Android TV OS 12. | Debug APK assembly and launcher metadata inspection are complete; verify launcher visibility, D-pad/Select/Back navigation, sign-in, and VPN behavior on the actual BRAVIA BF1. |

Sony uses both Android-based TVs and other TV operating systems. The identified target is Sony BRAVIA BF1 with Android TV OS 12. A successful APK build does not prove TV runtime support; the owner still needs to verify remote input and VPN behavior on the physical TV. See [`ANDROID_TV_SUPPORT.md`](ANDROID_TV_SUPPORT.md) for the device profile and acceptance checklist. References: [Sony's model/OS identification guide](https://www.sony.com/electronics/support/audio-video/articles/00200248), [Android TV navigation](https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv), and [Android TV controller requirements](https://developer.android.com/training/tv/get-started/controllers).

## Architecture categories

| Category | Location | Owns | Boundary |
|---|---|---|---|
| Android client | `android/` | Phone UI, Android lifecycle, permissions, VPN service, app resources, and phone/TV packaging. | Android APIs stay here. TV screens branch on Leanback and must remain D-pad operable. |
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
- Android TV launcher, focus treatment, remote-first screens, and the Sony device profile belong in `android/` and `docs/ANDROID_TV_SUPPORT.md`.
- Windows tray, installer, native helper integration, and Windows settings belong in `desktop/` or `helper/`, according to ownership in `AGENTS.md`.
- Brand-wide colors, spacing, typography, and icons belong in `design/` and its platform mirrors.

## Sign-in flow labels

| Flow | Code owner | Status note |
|---|---|---|
| Email/password and email confirmation | `core/.../data/FxaAuthRepository.kt`; platform login screens | Existing sign-in path; password login can be rejected by a network security challenge. Do not attempt to bypass that challenge. |
| Browser sign-in with PKCE | `core/.../data/BrowserSignIn.kt`, platform login screens | Disabled on Windows, Android phone, and Android TV until Mozilla registers Ember's redirect URI for a dedicated Firefox Account OAuth client. The current redirect is rejected as `Incorrect redirect_uri`; see [`FXA_BROWSER_SIGN_IN.md`](FXA_BROWSER_SIGN_IN.md). |
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
- [`FXA_BROWSER_SIGN_IN.md`](FXA_BROWSER_SIGN_IN.md) — browser OAuth registration blocker and the steps required before enabling the flow.
- [`design/README.md`](../design/README.md) — design assets and attributions.
- [`history/2026-10-29-ember-sign-in.md`](history/2026-10-29-ember-sign-in.md) — archived sign-in task brief and completion record.

## Connection diagnostics

Android and Windows log monotonic durations for server selection, account/Guardian proxy-pass acquisition, upstream HTTP/2 setup, and total connect success or failure time. Android also records edge DNS and TUN setup durations; Windows records edge IPv4 resolution in whole-device mode. These stage labels let exported logs distinguish a slow control-plane request, DNS lookup, tunnel handshake, or retry without logging account credentials or tokens.

Android edge DNS answers remain cached for the resolver's five-minute TTL and are invalidated when an underlying network becomes available or is lost. Android control-plane requests try the configured bootstrap DNS-over-HTTPS resolver only after system DNS returns `UnknownHostException`; choosing System DNS disables that fallback.
