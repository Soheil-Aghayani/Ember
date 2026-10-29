# Archived work brief: Ember sign-in 1.0.51

You are working on Ember, a Windows PC + Android phone VPN client (Firefox-account sign-in, Mozilla's free VPN edge). The owner also wants Sony Smart TV support; treat that as a planned target, identify the exact model/OS, and do not call the current phone app TV-compatible.
Repo: `D:\Programming\FoxyVPN-work` (git worktree, branch `work/untrusted-wifi`). Read `AGENTS.md` first and follow it.
Rules that matter: work locally only (no GitHub push/Actions/releases); do not touch `D:\Programming\FoxyVPN` (another session's files); never run the installer or network helpers; no secrets; match surrounding code style.
Do not write, change or run anything that solves or evades Fastly's bot challenge (`FastlyChallengeSolver.kt` stays as it is).

> **Status: completed on 2026-10-29.** This is a historical task brief and verification record. For current platform status and architecture, use [`../../AGENTS.md`](../../AGENTS.md) and [`../PROJECT_MAP.md`](../PROJECT_MAP.md).
> Owner-added product target: Sony Smart TV is planned. The model and OS remain unidentified; do not claim the phone app is TV-compatible.

## Completion record

- [x] Replaced the Android QR scanner with ZXing and verified the merged CAMERA permission.
- [x] Added JVM checks for one-time transfer round-trip, wrong passphrase, damaged code, and expiry.
- [x] Built the Android APK; emulator showed both QR and manual entry, and a fake damaged code produced the expected message.
- [x] Built the Windows distributable and compiled the 1.0.51 installer. The installer was not run.
- [x] Copied the installer and APK to `C:\Users\Soheil\Downloads\Programs`; preserved the older APK as a dated backup.
- [x] Committed locally on `work/untrusted-wifi`; generated `core/build/` files are now ignored and untracked from the repository while local outputs remain.
- [ ] Owner verification remains for a real QR scan, Firefox Account browser sign-in, and visual Windows UI. No real account credentials were used.
- [ ] Sony Smart TV implementation remains planned until the owner provides the TV model and OS.

## Background
Password sign-in (`POST api.accounts.firefox.com/v1/account/login`) now returns HTTP 406 from Fastly on the owner's network, for every client including the original FoxyVPN code. Token refresh (`/v1/oauth/token`) is not blocked. So I added two ways to sign in without that call:
1. **Sign-in code** (`core/.../data/SessionTransfer.kt`): a signed-in device (Windows Settings card) shows an AES-GCM-encrypted code + passphrase holding the OAuth refresh token; the other device enters it. `FxaAuthRepository.signInWithRefreshToken` uses it.
2. **Browser sign-in** (`core/.../data/BrowserSignIn.kt`): opens accounts.firefox.com/authorization (client_id 5882386c6d801776, PKCE, redirect `http://127.0.0.1:<port>/callback`), then `FxaAuthRepository.signInWithAuthorizationCode`. Unknown whether FxA accepts that redirect URI; the owner must test it with a real sign-in.
Both have buttons on the Windows login page (`desktop/.../ui/App.kt`, `SessionTransferUi.kt`) and Android `LoginScreen.kt`.

## Current state at the start of this task
Last commit: `d7112b3` (installer 1.0.50). Uncommitted edits on top:
- Fixed bug: code sign-in failed with "Not signed in" because both token stores ignore a blank access token; `signInWithRefreshToken` now saves the placeholder `"pending"` (expired) instead of `""`.
- `SessionTransfer.Created.qrPayload` (`code#passphrase`) and `SessionTransfer.openPayload(...)`.
- Windows: ZXing core dependency (`desktop/build.gradle.kts`), QR image on the "Sign in another device" card (`SessionTransferUi.kt`), strings `transferQr` (EN + FA).
- Android: `LoginScreen.kt` has a "Scan QR code" button, `doTransfer(...)`, and uses the **ML Kit** scanner. That library cannot be downloaded (Google's maven returns 404 here, and likely in Iran), so **the Android build is currently broken**.

## Original task checklist (completed items are recorded above)
1. Android QR scanner without Google services.
2. Build both apps and build the desktop installer as `EMBER_VERSION=1.0.51`.
3. Check QR/manual sign-in entry and fake-code errors on the Android emulator; test crypto in plain JVM code with fake data only.
4. Compile the Windows Settings card and report that visual UI confirmation is unavailable.
5. Copy installer and APK to `C:\Users\Soheil\Downloads\Programs` and commit locally.
6. Report unverified real QR scan, real browser sign-in, and Windows UI.

## Build commands (Windows; Git Bash mangles `/D` flags, so run ISCC from PowerShell)
- Gradle: `D:\Programming\tools\gradle\gradle-8.10.2\bin\gradle.bat`; `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`; add `C:\Users\Soheil\.cargo\bin` to PATH.
- Android APK: `gradle :android:assembleDebug` → `android\build\outputs\apk\debug\android-debug.apk` (SDK in `D:\Programming\tools\android-sdk`, set in untracked `local.properties`).
- Windows app folder: `$env:EMBER_VERSION='1.0.51'; gradle -p desktop createDistributable`.
- Installer (PowerShell, from `desktop\`): `& D:\Programming\tools\InnoSetup\ISCC.exe /DVersion=1.0.51 "/DSourceDir=D:\Programming\FoxyVPN-work\desktop\build\compose\binaries\main\app\Ember" "/DOutputDir=D:\Programming\FoxyVPN-work\desktop\build\installer" packaging\ember.iss`
- Emulator: AVD `ember_test`. Start: `D:\Programming\tools\android-sdk\emulator\emulator.exe -avd ember_test -no-snapshot -no-audio -gpu swiftshader_indirect`; adb: `D:\Programming\tools\android-sdk\platform-tools\adb.exe`. Install with `adb install -r <apk>`; package `com.soheil.ember`.

## Tips
- Use the editor tool for edits; Python heredocs with Windows paths and backslashes cause escape bugs.
- `core` is JVM-only: no Android APIs there.
- Do not edit `desktop/appResources/windows-x64/ember-tunnel.ps1` or `core/.../vpn/`.

## Separate open bug (only if the owner provides logs)
The native Rust helper (`helper/`) fails every start with "The parameter is incorrect (0x80070057)", so Ember falls back to the slow PowerShell helper. Since 1.0.47 errors name the failing step and the log is kept as `%APPDATA%\Ember\tunnel-state\helper-native.log`. Ask the owner to connect once and paste that file, then fix the failing step in `helper/src/windows/*.rs` and run `cargo test` in `helper/`.
